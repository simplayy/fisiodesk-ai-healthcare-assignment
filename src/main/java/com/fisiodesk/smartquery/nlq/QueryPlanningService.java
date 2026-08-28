package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.config.AiProperties;
import com.fisiodesk.smartquery.domain.StoredQueryPlan;
import com.fisiodesk.smartquery.repository.StoredQueryPlanRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Question to plan, under a time budget. Known questions come from the
 * query_plans collection; a new one goes to the LLM for at most
 * plan-timeout-ms, past that the rule-based plan answers and the LLM plan is
 * stored for next time. Concurrent requests for the same question share one
 * LLM call.
 */
@Service
public class QueryPlanningService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanningService.class);

    private final LlmQueryPlanner llmPlanner;
    private final HeuristicQueryPlanner heuristicPlanner;
    private final AiProperties aiProperties;
    private final ExecutorService llmExecutor;
    private final StoredQueryPlanRepository planRepository;

    private final ConcurrentMap<String, CompletableFuture<QueryPlan>> inFlight = new ConcurrentHashMap<>();

    public QueryPlanningService(LlmQueryPlanner llmPlanner,
                                HeuristicQueryPlanner heuristicPlanner,
                                AiProperties aiProperties,
                                ExecutorService llmExecutor,
                                StoredQueryPlanRepository planRepository) {
        this.llmPlanner = llmPlanner;
        this.heuristicPlanner = heuristicPlanner;
        this.aiProperties = aiProperties;
        this.llmExecutor = llmExecutor;
        this.planRepository = planRepository;
    }

    /** Plans the question and reports where the plan came from and how long it took. */
    public PlanOutcome planWithOrigin(String question) {
        long start = System.nanoTime();
        List<PlanClue> clues = heuristicPlanner.explain(question);
        if (!aiProperties.enabled()) {
            return new PlanOutcome(heuristicPlanner.plan(question), PlanOutcome.Origin.AI_DISABLED, elapsedMs(start), clues);
        }
        String key = key(question);
        Optional<QueryPlan> known = findStored(key);
        if (known.isPresent()) {
            return new PlanOutcome(known.get(), PlanOutcome.Origin.STORED, elapsedMs(start), clues);
        }
        PlanOutcome.Origin origin;
        try {
            QueryPlan plan = llmPlanAsync(key, question).get(aiProperties.planTimeoutMs(), TimeUnit.MILLISECONDS);
            return new PlanOutcome(plan, PlanOutcome.Origin.LLM, elapsedMs(start), clues);
        } catch (TimeoutException e) {
            log.info("LLM planner over budget ({} ms), answering with rule-based plan",
                    aiProperties.planTimeoutMs());
            origin = PlanOutcome.Origin.LLM_TIMEOUT;
        } catch (ExecutionException e) {
            log.warn("LLM planner unavailable, using rule-based plan: {}", e.getCause().getMessage());
            origin = PlanOutcome.Origin.LLM_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            origin = PlanOutcome.Origin.LLM_ERROR;
        }
        return new PlanOutcome(heuristicPlanner.plan(question), origin, elapsedMs(start), clues);
    }

    /** Forgets every stored plan: the next occurrence of each question is planned again. */
    public long clearStoredPlans() {
        long count = planRepository.count();
        planRepository.deleteAll();
        return count;
    }

    /** Plans a question outside any request (suggested queries at startup). */
    public void warmUp(String question) {
        if (!aiProperties.enabled()) {
            return;
        }
        String key = key(question);
        if (findStored(key).isEmpty()) {
            llmPlanAsync(key, question);
        }
    }

    /**
     * A few real LLM calls at startup, results discarded: after a restart the
     * first call pays HTTPS setup and JIT (~0.8 s), enough to miss the budget.
     */
    public void primeLlmClient(String question, int calls) {
        if (!aiProperties.enabled()) {
            return;
        }
        llmExecutor.submit(() -> {
            for (int i = 0; i < calls; i++) {
                long start = System.nanoTime();
                try {
                    llmPlanner.plan(question);
                    log.info("LLM client primed ({}/{}) in {} ms", i + 1, calls, elapsedMs(start));
                } catch (RuntimeException e) {
                    log.warn("LLM client priming failed: {}", e.getMessage());
                    return;
                }
            }
        });
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private CompletableFuture<QueryPlan> llmPlanAsync(String key, String question) {
        boolean explicitWindow = heuristicPlanner.explain(question).stream()
                .anyMatch(clue -> "windowMonths".equals(clue.field()));
        return inFlight.computeIfAbsent(key, k -> {
            long start = System.nanoTime();
            CompletableFuture<QueryPlan> future = CompletableFuture.supplyAsync(
                    () -> PlanGuardrail.merge(llmPlanner.plan(question), heuristicPlanner.plan(question), explicitWindow),
                    llmExecutor);
            future.whenComplete((plan, error) -> {
                inFlight.remove(k);
                if (plan != null) {
                    log.info("LLM planned '{}' in {} ms", question, elapsedMs(start));
                    store(k, question, plan);
                } else if (error != null) {
                    log.warn("LLM planning failed for '{}': {}", question, error.getMessage());
                }
            });
            return future;
        });
    }

    private Optional<QueryPlan> findStored(String key) {
        try {
            return planRepository.findById(key).map(this::toPlan);
        } catch (RuntimeException e) {
            log.warn("Plan store unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void store(String key, String question, QueryPlan plan) {
        try {
            StoredQueryPlan stored = new StoredQueryPlan();
            stored.setId(key);
            stored.setQuestion(question);
            stored.setBodyAreas(plan.bodyAreas().stream().map(Enum::name).collect(Collectors.toSet()));
            stored.setRequireImprovement(plan.requireImprovement());
            stored.setRequireWorsening(plan.requireWorsening());
            stored.setWindowMonths(plan.windowMonths());
            stored.setMissedLastAppointment(plan.missedLastAppointment());
            stored.setPlanner(plan.planner());
            stored.setCreatedAt(Instant.now());
            planRepository.save(stored);
        } catch (RuntimeException e) {
            log.warn("Could not persist plan: {}", e.getMessage());
        }
    }

    private QueryPlan toPlan(StoredQueryPlan stored) {
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        if (stored.getBodyAreas() != null) {
            stored.getBodyAreas().forEach(name -> areas.add(BodyArea.valueOf(name)));
        }
        return new QueryPlan(areas, stored.isRequireImprovement(), stored.isRequireWorsening(),
                stored.getWindowMonths(), stored.isMissedLastAppointment(), stored.getPlanner());
    }

    /** Questions are keyed by a hash of their normalized text. */
    static String key(String question) {
        String normalized = question.trim().toLowerCase(Locale.ITALIAN).replaceAll("\\s+", " ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
