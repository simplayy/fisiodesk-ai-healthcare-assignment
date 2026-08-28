package com.fisiodesk.smartquery.nlq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fisiodesk.smartquery.analysis.BodyArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeuristicQueryPlannerTest {

    private HeuristicQueryPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new HeuristicQueryPlanner();
    }

    @Test
    void parsesTheTargetQuery() {
        QueryPlan plan = planner.plan(
                "Mostra pazienti con dolore lombare che hanno mostrato miglioramento "
                + "negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento");

        assertThat(plan.bodyAreas()).containsExactly(BodyArea.LUMBAR);
        assertThat(plan.requireImprovement()).isTrue();
        assertThat(plan.requireWorsening()).isFalse();
        assertThat(plan.windowMonths()).isEqualTo(3);
        assertThat(plan.missedLastAppointment()).isTrue();
    }

    @Test
    void parsesWorseningQueries() {
        QueryPlan plan = planner.plan(
                "Pazienti con lombalgia che non mostrano miglioramento negli ultimi 3 mesi");

        assertThat(plan.bodyAreas()).containsExactly(BodyArea.LUMBAR);
        assertThat(plan.requireImprovement()).isFalse();
        assertThat(plan.requireWorsening()).isTrue();
    }

    @Test
    void parsesCustomWindowAndOtherAreas() {
        QueryPlan plan = planner.plan(
                "Pazienti con cervicalgia in miglioramento negli ultimi 2 mesi");

        assertThat(plan.bodyAreas()).containsExactly(BodyArea.CERVICAL);
        assertThat(plan.requireImprovement()).isTrue();
        assertThat(plan.windowMonths()).isEqualTo(2);
        assertThat(plan.missedLastAppointment()).isFalse();
    }

    @Test
    void negationIsCaughtEvenWithWordsInBetween() {
        for (String question : new String[] {
                "chi non ha ancora avuto miglioramenti?",
                "pazienti che non hanno mostrato alcun miglioramento",
                "pazienti con lombalgia senza alcun segno di miglioramento" }) {
            QueryPlan plan = planner.plan(question);
            assertThat(plan.requireWorsening()).as(question).isTrue();
            assertThat(plan.requireImprovement()).as(question).isFalse();
        }
    }

    @Test
    void explainsWhichWordsDroveEachField() {
        var clues = planner.explain(
                "Mostra pazienti con dolore lombare che hanno mostrato miglioramento "
                + "negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento");

        assertThat(clues).extracting(PlanClue::field, PlanClue::term, PlanClue::value).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("bodyAreas", "lombare", "LUMBAR"),
                org.assertj.core.groups.Tuple.tuple("requireImprovement", "miglioramento", "true"),
                org.assertj.core.groups.Tuple.tuple("missedLastAppointment", "saltato", "true"),
                org.assertj.core.groups.Tuple.tuple("windowMonths", "ultimi 3 mesi", "3"));
    }

    @Test
    void negationIsReportedAsTheWorseningClue() {
        var clues = planner.explain("Pazienti con lombalgia che non mostrano miglioramento");

        assertThat(clues).extracting(PlanClue::field).contains("requireWorsening").doesNotContain("requireImprovement");
        assertThat(clues).filteredOn(c -> c.field().equals("requireWorsening"))
                .extracting(PlanClue::term).containsExactly("non mostrano miglioramento");
    }

    @Test
    void defaultsWhenNothingIsSpecified() {
        QueryPlan plan = planner.plan("Chi ha saltato l'ultimo appuntamento?");

        assertThat(plan.bodyAreas()).isEmpty();
        assertThat(plan.requireImprovement()).isFalse();
        assertThat(plan.windowMonths()).isEqualTo(QueryPlan.DEFAULT_WINDOW_MONTHS);
        assertThat(plan.missedLastAppointment()).isTrue();
    }
}
