package com.fisiodesk.smartquery.query;

import com.fisiodesk.smartquery.domain.EventoCalendario;
import com.fisiodesk.smartquery.domain.NoteInsight;
import com.fisiodesk.smartquery.domain.Paziente;
import com.fisiodesk.smartquery.nlq.QueryPlan;
import com.fisiodesk.smartquery.query.QueryDtos.AppointmentView;
import com.fisiodesk.smartquery.query.QueryDtos.EvidenceView;
import com.fisiodesk.smartquery.query.QueryDtos.PatientResult;
import com.fisiodesk.smartquery.repository.EventoCalendarioRepository;
import com.fisiodesk.smartquery.repository.NoteInsightRepository;
import com.fisiodesk.smartquery.repository.PazienteRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Executes a {@link QueryPlan} against pre-computed insights. Everything here
 * is plain MongoDB reads over indexed collections - no AI calls - which is
 * what keeps the endpoint within the latency budget regardless of how many
 * professionals are querying concurrently.
 */
@Service
public class SmartQueryService {

    private final NoteInsightRepository insightRepository;
    private final EventoCalendarioRepository eventoRepository;
    private final PazienteRepository pazienteRepository;

    public SmartQueryService(NoteInsightRepository insightRepository,
                             EventoCalendarioRepository eventoRepository,
                             PazienteRepository pazienteRepository) {
        this.insightRepository = insightRepository;
        this.eventoRepository = eventoRepository;
        this.pazienteRepository = pazienteRepository;
    }

    public List<PatientResult> execute(QueryPlan plan, Instant referenceDate) {
        Instant from = referenceDate.atZone(ZoneOffset.UTC)
                .minusMonths(plan.windowMonths())
                .toInstant();

        Map<ObjectId, List<NoteInsight>> byPatient =
                insightRepository.findByDataBetween(from, referenceDate).stream()
                        .filter(insight -> matchesArea(insight, plan))
                        .collect(Collectors.groupingBy(NoteInsight::getPazienteId));

        Map<String, Paziente> patients = pazienteRepository
                .findAllById(byPatient.keySet().stream().map(ObjectId::toHexString).toList())
                .stream()
                .collect(Collectors.toMap(Paziente::getId, Function.identity()));

        return byPatient.entrySet().stream()
                .map(entry -> evaluate(entry.getKey(), entry.getValue(), plan,
                        referenceDate, patients))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(PatientResult::improvementScore).reversed()
                        .thenComparing(PatientResult::cognome))
                .toList();
    }

    private boolean matchesArea(NoteInsight insight, QueryPlan plan) {
        if (plan.bodyAreas().isEmpty()) {
            return true;
        }
        return insight.getBodyAreas() != null && plan.bodyAreas().stream()
                .anyMatch(area -> insight.getBodyAreas().contains(area.name()));
    }

    private Optional<PatientResult> evaluate(ObjectId pazienteId,
                                             List<NoteInsight> insights,
                                             QueryPlan plan,
                                             Instant referenceDate,
                                             Map<String, Paziente> patients) {
        // Clinical trend over the window, computed only on the notes that match
        // the requested body area: a patient improving on a different problem
        // (e.g. cervicalgia) must not pass a lumbar-improvement filter.
        int totalScore = insights.stream().mapToInt(NoteInsight::getImprovementScore).sum();
        boolean hasPositiveNote = insights.stream()
                .anyMatch(insight -> insight.getImprovementScore() > 0);

        if (plan.requireImprovement() && !(totalScore > 0 && hasPositiveNote)) {
            return Optional.empty();
        }
        if (plan.requireWorsening() && totalScore >= 0) {
            return Optional.empty();
        }

        Optional<EventoCalendario> lastEvent = eventoRepository
                .findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(pazienteId, referenceDate);
        boolean missedLast = lastEvent
                .map(event -> EventoCalendario.STATO_NO_SHOW.equals(event.getStato()))
                .orElse(false);
        if (plan.missedLastAppointment() && !missedLast) {
            return Optional.empty();
        }

        Paziente paziente = patients.get(pazienteId.toHexString());
        if (paziente == null) {
            return Optional.empty();
        }

        List<EvidenceView> evidence = insights.stream()
                .sorted(Comparator.comparing(NoteInsight::getData).reversed())
                .map(insight -> new EvidenceView(
                        insight.getSourceCollection(),
                        insight.getData(),
                        insight.getExcerpt(),
                        insight.getImprovementScore(),
                        insight.getEvidence(),
                        insight.getAnalyzer()))
                .toList();

        AppointmentView appointment = lastEvent
                .map(event -> new AppointmentView(event.getData(), event.getStato(), event.getNote()))
                .orElse(null);

        return Optional.of(new PatientResult(
                paziente.getId(),
                paziente.getNome(),
                paziente.getCognome(),
                paziente.getEta(),
                paziente.getEmail(),
                paziente.getTelefono(),
                paziente.getStato(),
                totalScore,
                appointment,
                evidence));
    }
}
