package com.fisiodesk.smartquery.nlq;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Plans a configured list of questions at startup (the suggested queries of the UI), so the first click is fast. */
@Component
@ConfigurationProperties(prefix = "smartquery.warmup")
public class PlanWarmup {

    private static final Logger log = LoggerFactory.getLogger(PlanWarmup.class);

    private final QueryPlanningService planningService;
    private List<String> questions = List.of();

    public PlanWarmup(QueryPlanningService planningService) {
        this.planningService = planningService;
    }

    public List<String> getQuestions() {
        return questions;
    }

    public void setQuestions(List<String> questions) {
        this.questions = questions == null ? List.of() : questions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (questions.isEmpty()) {
            return;
        }
        log.info("Warming up plans for {} questions", questions.size());
        questions.forEach(planningService::warmUp);
        // Real calls to open the connection and warm the client path.
        planningService.primeLlmClient(questions.get(0), 3);
    }
}
