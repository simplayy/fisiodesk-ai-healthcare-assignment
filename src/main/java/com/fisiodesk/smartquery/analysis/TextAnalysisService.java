package com.fisiodesk.smartquery.analysis;

import com.fisiodesk.smartquery.llm.LlmClient;
import com.fisiodesk.smartquery.llm.LlmException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Chooses the analyzer for each note: LLM when configured, with automatic
 * degradation to the deterministic analyzer on any failure. The system keeps
 * answering (with slightly coarser classification) even if the AI provider is
 * down or the budget is exhausted.
 */
@Service
public class TextAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TextAnalysisService.class);

    private final HeuristicClinicalTextAnalyzer heuristicAnalyzer;
    private final LlmClinicalTextAnalyzer llmAnalyzer;
    private final LlmClient llmClient;

    public TextAnalysisService(HeuristicClinicalTextAnalyzer heuristicAnalyzer,
                               LlmClinicalTextAnalyzer llmAnalyzer,
                               LlmClient llmClient) {
        this.heuristicAnalyzer = heuristicAnalyzer;
        this.llmAnalyzer = llmAnalyzer;
        this.llmClient = llmClient;
    }

    public ClinicalNoteAnalysis analyze(String text) {
        if (llmClient.isEnabled()) {
            try {
                return llmAnalyzer.analyze(text);
            } catch (LlmException e) {
                log.warn("Falling back to heuristic analyzer: {}", e.getMessage());
            }
        }
        return heuristicAnalyzer.analyze(text);
    }

    /** Stable fingerprint of the note text, used to skip re-analysis of unchanged notes. */
    public String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
