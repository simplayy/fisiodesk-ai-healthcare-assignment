package com.fisiodesk.smartquery.query;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.nlq.PlanOutcome;
import com.fisiodesk.smartquery.nlq.QueryPlan;
import com.fisiodesk.smartquery.nlq.QueryPlanningService;
import com.fisiodesk.smartquery.query.QueryDtos.ClueView;
import com.fisiodesk.smartquery.query.QueryDtos.ExclusionView;
import com.fisiodesk.smartquery.query.QueryDtos.ExplainView;
import com.fisiodesk.smartquery.query.QueryDtos.Meta;
import com.fisiodesk.smartquery.query.QueryDtos.PlanView;
import com.fisiodesk.smartquery.query.QueryDtos.QueryResponse;
import com.fisiodesk.smartquery.query.QueryDtos.StageView;
import com.fisiodesk.smartquery.repository.EventoCalendarioRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** API entry point: resolves the reference date, plans, executes, caches repeated questions for 60 s. */
@Service
public class QueryFacade {

    private final QueryPlanningService planningService;
    private final SmartQueryService queryService;
    private final EventoCalendarioRepository eventoRepository;
    private final String configuredReferenceDate;

    private final Cache<String, QueryResponse> responseCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public QueryFacade(QueryPlanningService planningService,
                       SmartQueryService queryService,
                       EventoCalendarioRepository eventoRepository,
                       @Value("${smartquery.reference-date:}") String configuredReferenceDate) {
        this.planningService = planningService;
        this.queryService = queryService;
        this.eventoRepository = eventoRepository;
        this.configuredReferenceDate = configuredReferenceDate;
    }

    /** @param professionistaId optional scope (in production it would come from the authenticated user) */
    public QueryResponse answer(String question, String referenceDateOverride, String professionistaId) {
        ReferenceDate referenceDate = resolveReferenceDate(referenceDateOverride);
        ObjectId professionista = parseProfessionista(professionistaId);
        String cacheKey = question.trim().toLowerCase(Locale.ITALIAN) + "|" + referenceDate.value() + "|" + professionista;

        QueryResponse cached = responseCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.asCached();
        }

        long start = System.nanoTime();
        PlanOutcome planned = planningService.planWithOrigin(question);
        QueryPlan plan = planned.plan();
        SmartQueryService.Execution execution = queryService.run(plan, referenceDate.value(), professionista);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        QueryResponse response = new QueryResponse(
                question, toView(plan, referenceDate.value()), execution.results(),
                new Meta(tookMs, false, referenceDate.value()),
                explain(planned, referenceDate, execution, tookMs));
        responseCache.put(cacheKey, response);
        return response;
    }

    /** Drops every cached answer; returns how many there were. */
    public long clearResponseCache() {
        responseCache.cleanUp();
        long size = responseCache.estimatedSize();
        responseCache.invalidateAll();
        return size;
    }

    private ExplainView explain(PlanOutcome planned, ReferenceDate referenceDate,
                                SmartQueryService.Execution execution, long tookMs) {
        SmartQueryService.Trace trace = execution.trace();
        boolean aiInThisRequest = planned.origin() == PlanOutcome.Origin.LLM;
        List<StageView> stages = List.of(
                new StageView("Interpretazione della domanda", planned.planMs(), planned.origin().label()),
                new StageView("Ricerca nelle note indicizzate", trace.insightsMs(),
                        trace.notesInWindow() + " note nel periodo, " + trace.notesMatchingArea()
                                + " sull'area richiesta, " + trace.patientsInWindow() + " pazienti"),
                new StageView("Valutazione dei pazienti", trace.evaluationMs(),
                        trace.patientsInWindow() + " pazienti valutati, " + execution.results().size()
                                + " inclusi, " + trace.excluded().size() + " esclusi"),
                new StageView("Totale", tookMs, aiInThisRequest
                        ? "di cui " + planned.planMs() + " ms di attesa dell'AI; la ricerca sulle note non ne usa"
                        : "nessuna chiamata AI in questa richiesta"));
        return new ExplainView(
                planned.origin().name(),
                planned.origin().label(),
                planned.clues().stream().map(c -> new ClueView(c.field(), c.term(), c.value())).toList(),
                referenceDate.source(),
                stages,
                trace.notesInWindow(),
                trace.notesMatchingArea(),
                trace.patientsInWindow(),
                trace.excluded().stream()
                        .map(e -> new ExclusionView(e.pazienteId(), e.nome(), e.cognome(), e.reason()))
                        .toList());
    }

    private static ObjectId parseProfessionista(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (!ObjectId.isValid(id.trim())) {
            throw new IllegalArgumentException("professionistaId non valido: " + id);
        }
        return new ObjectId(id.trim());
    }

    /** The demo dataset is Mar-Dec 2024, so "today" is: request parameter, then REFERENCE_DATE, then the latest calendar event. */
    private record ReferenceDate(Instant value, String source) {
    }

    private ReferenceDate resolveReferenceDate(String override) {
        if (override != null && !override.isBlank()) {
            return new ReferenceDate(parseDay(override), "indicata nella richiesta");
        }
        if (configuredReferenceDate != null && !configuredReferenceDate.isBlank()) {
            return new ReferenceDate(parseDay(configuredReferenceDate), "configurazione (REFERENCE_DATE)");
        }
        return eventoRepository.findFirstByOrderByDataDesc()
                .map(event -> new ReferenceDate(event.getData(), "data dell'ultimo evento in calendario"))
                .orElseGet(() -> new ReferenceDate(Instant.now(), "data odierna"));
    }

    private static Instant parseDay(String day) {
        return LocalDate.parse(day.trim()).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
    }

    private PlanView toView(QueryPlan plan, Instant referenceDate) {
        Instant from = referenceDate.atZone(ZoneOffset.UTC).minusMonths(plan.windowMonths()).toInstant();
        return new PlanView(
                plan.bodyAreas().stream().map(BodyArea::name).collect(Collectors.toSet()),
                plan.requireImprovement(),
                plan.requireWorsening(),
                plan.windowMonths(),
                plan.missedLastAppointment(),
                plan.attendedLastAppointment(),
                plan.planner(),
                from,
                referenceDate);
    }
}
