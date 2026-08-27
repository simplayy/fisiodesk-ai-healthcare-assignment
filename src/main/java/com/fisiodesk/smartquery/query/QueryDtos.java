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

    public record QueryResponse(
            String question,
            PlanView plan,
            List<PatientResult> results,
            Meta meta) {

        public QueryResponse asCached() {
            return new QueryResponse(question, plan, results,
                    new Meta(meta.tookMs(), true, meta.referenceDate()));
        }
    }
}
