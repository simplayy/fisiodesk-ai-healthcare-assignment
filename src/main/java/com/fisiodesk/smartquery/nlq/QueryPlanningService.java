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
 * Turns a question into a {@link QueryPlan} under a latency budget.
 *
 * Known questions are served from the {@code query_plans} collection (a plan
 * does not depend on the data, so it is computed once per question, across
 * restarts and instances). For a new question the LLM planner is started
 * and awaited for at most {@code smartquery.ai.plan-timeout-ms}; past that
 * the request is answered with the rule-based plan while the LLM plan is
 * completed in the background and stored for the next time. Concurrent
 * requests for the same question share a single LLM call.
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

    public QueryPlan plan(String question) {
        if (!aiProperties.enabled()) {
            return heuristicPlanner.plan(question);
        }
        String key = key(question);
        Optional<QueryPlan> known = findStored(key);
        if (known.isPresent()) {
            return known.get();
        }
        try {
            return llmPlanAsync(key, question).get(aiProperties.planTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("LLM planner over budget ({} ms), answering with rule-based plan",
                    aiProperties.planTimeoutMs());
        } catch (ExecutionException e) {
            log.warn("LLM planner unavailable, using rule-based plan: {}", e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return heuristicPlanner.plan(question);
    }

    /**
     * Pre-computes the LLM plan for a question outside any request, e.g. for
     * suggested or saved queries at startup.
     */
    public void warmUp(String question) {
        if (!aiProperties.enabled()) {
            return;
        }
        String key = key(question);
        if (findStored(key).isEmpty()) {
            llmPlanAsync(key, question);
        }
    }

    private CompletableFuture<QueryPlan> llmPlanAsync(String key, String question) {
        return inFlight.computeIfAbsent(key, k -> {
            CompletableFuture<QueryPlan> future = CompletableFuture.supplyAsync(
                    () -> PlanGuardrail.merge(llmPlanner.plan(question), heuristicPlanner.plan(question)),
                    llmExecutor);
            future.whenComplete((plan, error) -> {
                inFlight.remove(k);
                if (plan != null) {
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
