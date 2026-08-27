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
    void defaultsWhenNothingIsSpecified() {
        QueryPlan plan = planner.plan("Chi ha saltato l'ultimo appuntamento?");

        assertThat(plan.bodyAreas()).isEmpty();
        assertThat(plan.requireImprovement()).isFalse();
        assertThat(plan.windowMonths()).isEqualTo(QueryPlan.DEFAULT_WINDOW_MONTHS);
        assertThat(plan.missedLastAppointment()).isTrue();
    }
}
