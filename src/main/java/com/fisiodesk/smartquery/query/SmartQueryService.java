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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/** Executes a {@link QueryPlan} over note_insights, eventi_calendario and pazienti: plain indexed reads, no AI. */
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

    /** Why a patient with notes in the window is not in the answer. */
    public record Exclusion(String pazienteId, String nome, String cognome, String reason) {
    }

    /** What the execution did, for the explanation shown with the answer. */
    public record Trace(long insightsMs, int notesInWindow, int notesMatchingArea,
                        int patientsInWindow, long evaluationMs,
                        List<Exclusion> excluded) {
    }

    public record Execution(List<PatientResult> results, Trace trace) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("UTC"));

    /**
     * @param professionistaId when set, only this professional's notes are read; null = no scoping
     * @return the matching patients plus a trace of what the execution did
     */
    public Execution run(QueryPlan plan, Instant referenceDate, ObjectId professionistaId) {
        Instant from = referenceDate.atZone(ZoneOffset.UTC)
                .minusMonths(plan.windowMonths())
                .toInstant();

        long t0 = System.nanoTime();
        List<NoteInsight> inWindow = professionistaId == null
                ? insightRepository.findByDataGreaterThanEqualAndDataLessThanEqual(from, referenceDate)
                : insightRepository.findByProfessionistaIdAndDataGreaterThanEqualAndDataLessThanEqual(
                        professionistaId, from, referenceDate);
        Map<ObjectId, List<NoteInsight>> allByPatient = inWindow.stream()
                .collect(Collectors.groupingBy(NoteInsight::getPazienteId));
        Map<ObjectId, List<NoteInsight>> byPatient = inWindow.stream()
                .filter(insight -> matchesArea(insight, plan))
                .collect(Collectors.groupingBy(NoteInsight::getPazienteId));

        Map<String, Paziente> patients = pazienteRepository
                .findAllById(allByPatient.keySet().stream().map(ObjectId::toHexString).toList())
                .stream()
                .collect(Collectors.toMap(Paziente::getId, Function.identity()));
        long insightsMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        List<Exclusion> excluded = new ArrayList<>();
        List<PatientResult> results = new ArrayList<>();
        for (Map.Entry<ObjectId, List<NoteInsight>> entry : byPatient.entrySet()) {
            Outcome outcome = evaluate(entry.getKey(), entry.getValue(), plan, referenceDate, patients, professionistaId);
            if (outcome.result() != null) {
                results.add(outcome.result());
            } else {
                excluded.add(exclusion(entry.getKey(), patients, outcome.reason()));
            }
        }
        for (ObjectId id : allByPatient.keySet()) {
            if (!byPatient.containsKey(id)) {
                excluded.add(exclusion(id, patients, "nessuna nota sull'area richiesta nella finestra"));
            }
        }
        long evaluationMs = (System.nanoTime() - t1) / 1_000_000;

        results.sort(Comparator.comparingInt(PatientResult::improvementScore).reversed()
                .thenComparing(PatientResult::cognome));
        excluded.sort(Comparator.comparing(Exclusion::cognome, Comparator.nullsLast(Comparator.naturalOrder())));
        int matching = byPatient.values().stream().mapToInt(List::size).sum();
        return new Execution(List.copyOf(results), new Trace(insightsMs, inWindow.size(), matching,
                allByPatient.size(), evaluationMs, List.copyOf(excluded)));
    }

    private static Exclusion exclusion(ObjectId id, Map<String, Paziente> patients, String reason) {
        Paziente p = patients.get(id.toHexString());
        return new Exclusion(id.toHexString(), p == null ? null : p.getNome(), p == null ? null : p.getCognome(), reason);
    }

    private record Outcome(PatientResult result, String reason) {
        static Outcome kept(PatientResult r) {
            return new Outcome(r, null);
        }

        static Outcome dropped(String reason) {
            return new Outcome(null, reason);
        }
    }

    private boolean matchesArea(NoteInsight insight, QueryPlan plan) {
        if (plan.bodyAreas().isEmpty()) {
            return true;
        }
        return insight.getBodyAreas() != null && plan.bodyAreas().stream()
                .anyMatch(area -> insight.getBodyAreas().contains(area.name()));
    }

    private Outcome evaluate(ObjectId pazienteId,
                             List<NoteInsight> insights,
                             QueryPlan plan,
                             Instant referenceDate,
                             Map<String, Paziente> patients,
                             ObjectId professionistaId) {
        // Trend computed only on the notes of the requested area.
        int totalScore = insights.stream().mapToInt(NoteInsight::getImprovementScore).sum();

        if (plan.requireImprovement() && totalScore <= 0) {
            return Outcome.dropped("nessun miglioramento nell'area richiesta (punteggio " + signed(totalScore)
                    + " su " + insights.size() + " note)");
        }
        // The sum alone would call "improving" a patient who got better months ago and is
        // worse now: the most recent note must not contradict the trend.
        NoteInsight latest = insights.stream().max(Comparator.comparing(NoteInsight::getData)).orElseThrow();
        if (plan.requireImprovement() && latest.getImprovementScore() < 0) {
            return Outcome.dropped("l'ultima nota sull'area, del " + DAY.format(latest.getData())
                    + ", segnala un peggioramento (punteggio complessivo " + signed(totalScore) + ")");
        }
        // "non migliora" includes patients with no clear signal, not only those getting worse.
        if (plan.requireWorsening() && totalScore > 0) {
            return Outcome.dropped("in miglioramento nell'area richiesta (punteggio " + signed(totalScore)
                    + " su " + insights.size() + " note)");
        }

        // With scoping, "the last appointment" is the last one with this professional.
        Optional<EventoCalendario> lastEvent = professionistaId == null
                ? eventoRepository.findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(pazienteId, referenceDate)
                : eventoRepository.findFirstByPazienteIdAndProfessionistaIdAndDataLessThanEqualOrderByDataDesc(
                        pazienteId, professionistaId, referenceDate);
        boolean missedLast = lastEvent
                .map(event -> EventoCalendario.STATO_NO_SHOW.equals(event.getStato()))
                .orElse(false);
        if (plan.missedLastAppointment() && !missedLast) {
            return Outcome.dropped(lastEvent
                    .map(event -> "ultimo appuntamento " + event.getStato() + " il " + DAY.format(event.getData()) + ", non saltato")
                    .orElse("nessun appuntamento in calendario"));
        }
        if (plan.attendedLastAppointment() && !lastEvent.map(e -> !EventoCalendario.STATO_NO_SHOW.equals(e.getStato())).orElse(false)) {
            return Outcome.dropped(lastEvent
                    .map(event -> "ultimo appuntamento saltato il " + DAY.format(event.getData()))
                    .orElse("nessun appuntamento in calendario"));
        }

        Paziente paziente = patients.get(pazienteId.toHexString());
        if (paziente == null) {
            return Outcome.dropped("anagrafica non trovata");
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

        return Outcome.kept(new PatientResult(
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

    private static String signed(int score) {
        return score > 0 ? "+" + score : String.valueOf(score);
    }
}
