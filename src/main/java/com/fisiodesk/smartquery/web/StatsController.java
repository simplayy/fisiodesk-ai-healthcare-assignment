package com.fisiodesk.smartquery.web;

import com.fisiodesk.smartquery.llm.LlmClient;
import com.fisiodesk.smartquery.repository.NoteInsightRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final NoteInsightRepository insightRepository;
    private final LlmClient llmClient;

    public StatsController(NoteInsightRepository insightRepository, LlmClient llmClient) {
        this.insightRepository = insightRepository;
        this.llmClient = llmClient;
    }

    /** Small status endpoint used by the UI footer and handy for smoke tests. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "notesIndexed", insightRepository.count(),
                "analyzedByLlm", insightRepository.countByAnalyzer("llm"),
                "analyzedByHeuristic", insightRepository.countByAnalyzer("heuristic"),
                "aiEnabled", llmClient.isEnabled(),
                "aiModel", llmClient.isEnabled() ? llmClient.model() : "n/a");
    }
}
