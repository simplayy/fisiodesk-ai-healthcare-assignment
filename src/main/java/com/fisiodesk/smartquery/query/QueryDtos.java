package com.fisiodesk.smartquery.query;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** API payloads, grouped here to keep the surface easy to scan. */
public final class QueryDtos {

    private QueryDtos() {
    }

    public record PlanView(
            Set<String> bodyAreas,
            boolean requireImprovement,
            boolean requireWorsening,
            int windowMonths,
            boolean missedLastAppointment,
            String planner,
            Instant from,
            Instant to) {
    }

    public record EvidenceView(
            String source,
            Instant data,
            String excerpt,
            int improvementScore,
            List<String> matchedTerms,
            String analyzer) {
    }

    public record AppointmentView(Instant data, String stato, String note) {
    }

    public record PatientResult(
            String pazienteId,
            String nome,
            String cognome,
            Integer eta,
            String email,
            String telefono,
            String stato,
            int improvementScore,
            AppointmentView lastAppointment,
            List<EvidenceView> evidence) {
    }

    public record Meta(long tookMs, boolean cached, Instant referenceDate) {
    }

    /** One word of the question and how it was read ("dolore lombare" -> LUMBAR). */
    public record ClueView(String field, String term, String value) {
    }

    /** One step of the pipeline with its duration and a human-readable detail. */
    public record StageView(String name, long ms, String detail) {
    }

    public record ExclusionView(String pazienteId, String nome, String cognome, String reason) {
    }

    /** How the answer was produced, built from what the pipeline did (no extra AI call). */
    public record ExplainView(
            String planOrigin,
            String planOriginLabel,
            List<ClueView> clues,
            String referenceDateSource,
            List<StageView> stages,
            int notesInWindow,
            int notesMatchingArea,
            int patientsInWindow,
            List<ExclusionView> excluded) {
    }

    public record QueryResponse(
            String question,
            PlanView plan,
            List<PatientResult> results,
            Meta meta,
            ExplainView explain) {

        public QueryResponse asCached() {
            return new QueryResponse(question, plan, results,
                    new Meta(meta.tookMs(), true, meta.referenceDate()), explain);
        }
    }
}
