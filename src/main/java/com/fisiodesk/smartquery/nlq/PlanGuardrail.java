package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import java.util.EnumSet;
import java.util.Set;

/**
 * Merges the LLM plan with the rule-based one: explicit markers found by the
 * lexicon are added to the model plan (small models sometimes drop a filter
 * that is spelled out in the question), paraphrases are left to the model,
 * worsening wins over improvement.
 */
public final class PlanGuardrail {

    private PlanGuardrail() {
    }

    /** @param explicitWindow the question states the window ("ultimi 3 mesi"), so the lexicon value wins */
    public static QueryPlan merge(QueryPlan llm, QueryPlan rules, boolean explicitWindow) {
        // Areas: the lexicon wins when it found any ("mal di schiena" has been mapped to the shoulder by a small model).
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        areas.addAll(rules.bodyAreas().isEmpty() ? llm.bodyAreas() : rules.bodyAreas());

        boolean worsening = llm.requireWorsening() || rules.requireWorsening();
        boolean improvement = !worsening && (llm.requireImprovement() || rules.requireImprovement());
        boolean missedLast = llm.missedLastAppointment() || rules.missedLastAppointment();

        int window = explicitWindow ? rules.windowMonths() : llm.windowMonths();

        return new QueryPlan(areas, improvement, worsening, window, missedLast, llm.planner());
    }
}
