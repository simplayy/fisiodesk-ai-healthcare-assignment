package com.fisiodesk.smartquery.nlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.config.AiProperties;
import com.fisiodesk.smartquery.repository.StoredQueryPlanRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryPlanningServiceTest {

    private static final String QUESTION =
            "Pazienti con dolore lombare migliorati negli ultimi 3 mesi che hanno saltato l'ultimo appuntamento";

    private static final QueryPlan LLM_PLAN = new QueryPlan(
            EnumSet.of(BodyArea.LUMBAR), true, false, 3, true, "llm");

    @Mock
    private LlmQueryPlanner llmPlanner;
    @Mock
    private StoredQueryPlanRepository planRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        when(planRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void slowLlmPlannerFallsBackWithinBudgetAndIsStoredForNextTime() {
        when(llmPlanner.plan(anyString())).thenAnswer(invocation -> {
            Thread.sleep(600);
            return LLM_PLAN;
        });

        long start = System.currentTimeMillis();
        QueryPlan first = service(true, 200).planWithOrigin(QUESTION).plan();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(first.planner()).isEqualTo("heuristic");
        assertThat(elapsed).isLessThan(550);
        verify(planRepository, timeout(2000)).save(any());
    }

    @Test
    void concurrentRequestsForTheSameQuestionShareOneLlmCall() throws Exception {
        when(llmPlanner.plan(anyString())).thenAnswer(invocation -> {
            Thread.sleep(400);
            return LLM_PLAN;
        });
        QueryPlanningService service = service(true, 100);

        ExecutorService clients = Executors.newFixedThreadPool(5);
        try {
            List<Future<QueryPlan>> calls = IntStream.range(0, 5)
                    .mapToObj(i -> clients.submit(() -> service.planWithOrigin(QUESTION).plan()))
                    .toList();
            for (Future<QueryPlan> call : calls) {
                assertThat(call.get().planner()).isEqualTo("heuristic");
            }
        } finally {
            clients.shutdownNow();
        }

        verify(planRepository, timeout(2000)).save(any());
        verify(llmPlanner, times(1)).plan(anyString());
    }

    @Test
    void fastLlmPlannerIsUsedDirectlyAndMergedWithLexicalMarkers() {
        // The model dropped the explicit "saltato l'ultimo appuntamento"; the merge restores it.
        when(llmPlanner.plan(anyString())).thenReturn(
                new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, false, "llm"));

        QueryPlan plan = service(true, 1000).planWithOrigin(QUESTION).plan();

        assertThat(plan.planner()).isEqualTo("llm");
        assertThat(plan.missedLastAppointment()).isTrue();
        // the plan is stored from the completion callback, i.e. asynchronously
        verify(planRepository, timeout(2000)).save(any());
    }

    @Test
    void failingLlmPlannerFallsBackToRules() {
        when(llmPlanner.plan(anyString())).thenThrow(new IllegalStateException("boom"));

        QueryPlan plan = service(true, 1000).planWithOrigin(QUESTION).plan();

        assertThat(plan.planner()).isEqualTo("heuristic");
        assertThat(plan.bodyAreas()).containsExactly(BodyArea.LUMBAR);
        assertThat(plan.missedLastAppointment()).isTrue();
    }

    @Test
    void disabledLlmNeverCallsThePlanner() {
        QueryPlan plan = service(false, 1000).planWithOrigin(QUESTION).plan();

        assertThat(plan.planner()).isEqualTo("heuristic");
        verify(llmPlanner, never()).plan(anyString());
    }

    private QueryPlanningService service(boolean aiEnabled, int planTimeoutMs) {
        return new QueryPlanningService(llmPlanner, new HeuristicQueryPlanner(),
                new AiProperties(aiEnabled, planTimeoutMs), executor, planRepository);
    }
}
