package com.fisiodesk.smartquery.query;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.nlq.QueryPlan;
import com.fisiodesk.smartquery.nlq.QueryPlanningService;
import com.fisiodesk.smartquery.query.QueryDtos.Meta;
import com.fisiodesk.smartquery.query.QueryDtos.PlanView;
import com.fisiodesk.smartquery.query.QueryDtos.QueryResponse;
import com.fisiodesk.smartquery.repository.EventoCalendarioRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entry point for the API layer: resolves the reference date, plans the
 * question, executes the plan and serves repeated questions from a
 * short-lived in-memory cache.
 */
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

    public QueryResponse answer(String question, String referenceDateOverride) {
        Instant referenceDate = resolveReferenceDate(referenceDateOverride);
        String cacheKey = question.trim().toLowerCase(Locale.ITALIAN) + "|" + referenceDate;

        QueryResponse cached = responseCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.asCached();
        }

        long start = System.nanoTime();
        QueryPlan plan = planningService.plan(question);
        var results = queryService.execute(plan, referenceDate);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        QueryResponse response = new QueryResponse(
                question, toView(plan, referenceDate), results, new Meta(tookMs, false, referenceDate));
        responseCache.put(cacheKey, response);
        return response;
    }

    /**
     * The sample dataset covers Mar-Dec 2024, so "today" would make every
     * relative window empty. Order of precedence: explicit request parameter,
     * REFERENCE_DATE configuration, latest calendar event in the database.
     */
    private Instant resolveReferenceDate(String override) {
        String candidate = (override != null && !override.isBlank())
                ? override : configuredReferenceDate;
        if (candidate != null && !candidate.isBlank()) {
            return LocalDate.parse(candidate.trim()).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        }
        return eventoRepository.findFirstByOrderByDataDesc()
                .map(event -> event.getData())
                .orElseGet(Instant::now);
    }

    private PlanView toView(QueryPlan plan, Instant referenceDate) {
        Instant from = referenceDate.atZone(ZoneOffset.UTC).minusMonths(plan.windowMonths()).toInstant();
        return new PlanView(
                plan.bodyAreas().stream().map(BodyArea::name).collect(Collectors.toSet()),
                plan.requireImprovement(),
                plan.requireWorsening(),
                plan.windowMonths(),
                plan.missedLastAppointment(),
                plan.planner(),
                from,
                referenceDate);
    }
}
