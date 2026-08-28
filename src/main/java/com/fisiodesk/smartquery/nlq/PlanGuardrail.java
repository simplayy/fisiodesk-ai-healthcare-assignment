package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import java.util.EnumSet;
import java.util.Set;

/**
 * Combines the LLM interpretation of a question with the rule-based one.
 *
 * The two parsers have complementary failure modes: the lexicon cannot
 * understand paraphrases it does not know, while a (small) language model
 * occasionally drops a filter that is spelled out verbatim in the question.
 * The lexicon only fires on explicit markers, so anything it detects is
 * added to the LLM plan; everything else (paraphrases, unusual wording) is
 * left to the model. Contradictions between "improving" and "worsening"
 * are resolved in favour of worsening, which is how negations read.
 */
public final class PlanGuardrail {

    private PlanGuardrail() {
    }

    public static QueryPlan merge(QueryPlan llm, QueryPlan rules) {
        // Anatomical terms are a closed vocabulary where the lexicon is precise
        // and small models are not ("mal di schiena" has been seen mapped to
        // the shoulder); the model's areas only count when the lexicon found none.
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        areas.addAll(rules.bodyAreas().isEmpty() ? llm.bodyAreas() : rules.bodyAreas());

        boolean worsening = llm.requireWorsening() || rules.requireWorsening();
        boolean improvement = !worsening && (llm.requireImprovement() || rules.requireImprovement());
        boolean missedLast = llm.missedLastAppointment() || rules.missedLastAppointment();

        // The window is only trusted from the rules when the question states
        // it explicitly; otherwise the model's reading (or the default) stands.
        int window = rules.windowMonths() != QueryPlan.DEFAULT_WINDOW_MONTHS
                ? rules.windowMonths() : llm.windowMonths();

        return new QueryPlan(areas, improvement, worsening, window, missedLast, llm.planner());
    }
}
