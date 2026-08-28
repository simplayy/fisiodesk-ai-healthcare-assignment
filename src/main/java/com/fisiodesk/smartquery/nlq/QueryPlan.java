package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import java.util.Set;

/**
 * Structured form of a question, computed once (LLM or rules) and executed
 * with plain MongoDB queries.
 *
 * @param bodyAreas              anatomical areas to filter on (empty = any)
 * @param requireImprovement     true when the question asks for improving patients
 * @param requireWorsening       true when the question asks for non-improving/worsening patients
 * @param windowMonths           lookback window in months for clinical evidence
 * @param missedLastAppointment  true when the question filters on a skipped last appointment
 * @param planner                which component produced the plan ("heuristic" or "llm")
 */
public record QueryPlan(
        Set<BodyArea> bodyAreas,
        boolean requireImprovement,
        boolean requireWorsening,
        int windowMonths,
        boolean missedLastAppointment,
        String planner) {

    public static final int DEFAULT_WINDOW_MONTHS = 3;
}
