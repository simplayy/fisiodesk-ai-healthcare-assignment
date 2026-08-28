package com.fisiodesk.smartquery.web;

import com.fisiodesk.smartquery.config.AiProperties;
import com.fisiodesk.smartquery.repository.NoteInsightRepository;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final NoteInsightRepository insightRepository;
    private final AiProperties aiProperties;
    private final String model;
    private final String analysisModel;

    public StatsController(NoteInsightRepository insightRepository,
                           AiProperties aiProperties,
                           @Value("${spring.ai.openai.chat.options.model}") String model,
                           @Value("${smartquery.ai.analysis-model:}") String analysisModel) {
        this.insightRepository = insightRepository;
        this.aiProperties = aiProperties;
        this.model = model;
        this.analysisModel = analysisModel == null || analysisModel.isBlank() ? model : analysisModel.trim();
    }

    /** Small status endpoint used by the UI footer and handy for smoke tests. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "notesIndexed", insightRepository.count(),
                "analyzedByLlm", insightRepository.countByAnalyzer("llm"),
                "analyzedByHeuristic", insightRepository.countByAnalyzer("heuristic"),
                "aiEnabled", aiProperties.enabled(),
                "aiModel", aiProperties.enabled() ? model : "n/a",
                "aiAnalysisModel", aiProperties.enabled() ? analysisModel : "n/a");
    }
}
