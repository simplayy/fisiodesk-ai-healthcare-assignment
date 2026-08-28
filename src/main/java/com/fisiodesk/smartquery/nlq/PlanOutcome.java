package com.fisiodesk.smartquery.nlq;

import java.util.List;

/**
 * A plan together with how it was obtained: which component produced it, how
 * long the request waited for it and which words of the question the lexicon
 * recognised. This is what the "how was this answered" panel is built from.
 */
public record PlanOutcome(QueryPlan plan, Origin origin, long planMs, List<PlanClue> clues) {

    public enum Origin {
        AI_DISABLED("parser a regole (AI disattivata)"),
        STORED("piano già memorizzato per questa domanda"),
        LLM("AI in questa richiesta, verificata dal lessico"),
        LLM_TIMEOUT("parser a regole: l'AI ha superato il budget di tempo (il suo piano sarà usato dalla prossima volta)"),
        LLM_ERROR("parser a regole: AI non disponibile");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
