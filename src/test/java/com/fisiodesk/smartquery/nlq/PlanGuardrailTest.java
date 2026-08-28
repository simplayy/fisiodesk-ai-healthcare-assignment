package com.fisiodesk.smartquery.nlq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisiodesk.smartquery.analysis.BodyArea;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanGuardrailTest {

    private static QueryPlan plan(Set<BodyArea> areas, boolean improvement, boolean worsening,
                                  int window, boolean missed, String planner) {
        return new QueryPlan(areas, improvement, worsening, window, missed, planner);
    }

    @Test
    void anExplicitWindowInTheQuestionWinsOverTheModel() {
        QueryPlan llm = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 6, false, "llm");
        QueryPlan rules = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, false, "heuristic");

        assertThat(PlanGuardrail.merge(llm, rules, true).windowMonths()).isEqualTo(3);
        assertThat(PlanGuardrail.merge(llm, rules, false).windowMonths()).isEqualTo(6);
    }

    @Test
    void explicitMarkersMissedByTheModelAreRestored() {
        // "guariti" is in the question, the model dropped it, the lexicon saw it.
        QueryPlan llm = plan(EnumSet.of(BodyArea.LUMBAR), false, false, 4, false, "llm");
        QueryPlan rules = plan(EnumSet.of(BodyArea.LUMBAR), true, false, 4, false, "heuristic");

        QueryPlan merged = PlanGuardrail.merge(llm, rules, false);

        assertThat(merged.requireImprovement()).isTrue();
        assertThat(merged.windowMonths()).isEqualTo(4);
        assertThat(merged.planner()).isEqualTo("llm");
    }

    @Test
    void lexiconAreasOverrideModelAreas() {
        QueryPlan llm = plan(EnumSet.of(BodyArea.SHOULDER), false, false, 3, true, "llm");
        QueryPlan rules = plan(EnumSet.of(BodyArea.LUMBAR), false, false, 3, true, "heuristic");

        assertThat(PlanGuardrail.merge(llm, rules, false).bodyAreas()).containsExactly(BodyArea.LUMBAR);
    }

    @Test
    void modelAreasAreUsedWhenTheLexiconFindsNone() {
        QueryPlan llm = plan(EnumSet.of(BodyArea.KNEE), false, false, 3, false, "llm");
        QueryPlan rules = plan(EnumSet.noneOf(BodyArea.class), false, false, 3, false, "heuristic");

        assertThat(PlanGuardrail.merge(llm, rules, false).bodyAreas()).containsExactly(BodyArea.KNEE);
    }

    @Test
    void modelParaphraseUnderstandingIsKept() {
        // "chi non viene piu' in studio": no lexicon marker, the model understood it.
        QueryPlan llm = plan(EnumSet.noneOf(BodyArea.class), false, false, 3, true, "llm");
        QueryPlan rules = plan(EnumSet.noneOf(BodyArea.class), false, false, 3, false, "heuristic");

        assertThat(PlanGuardrail.merge(llm, rules, false).missedLastAppointment()).isTrue();
    }

    @Test
    void worseningWinsOverImprovement() {
        QueryPlan llm = plan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, false, "llm");
        QueryPlan rules = plan(EnumSet.of(BodyArea.LUMBAR), false, true, 3, false, "heuristic");

        QueryPlan merged = PlanGuardrail.merge(llm, rules, false);

        assertThat(merged.requireWorsening()).isTrue();
        assertThat(merged.requireImprovement()).isFalse();
    }

    @Test
    void modelWindowIsUsedWhenTheQuestionDoesNotStateOne() {
        QueryPlan llm = plan(EnumSet.of(BodyArea.KNEE), false, false, 6, false, "llm");
        QueryPlan rules = plan(EnumSet.of(BodyArea.KNEE), false, false,
                QueryPlan.DEFAULT_WINDOW_MONTHS, false, "heuristic");

        assertThat(PlanGuardrail.merge(llm, rules, false).windowMonths()).isEqualTo(6);
    }
}
