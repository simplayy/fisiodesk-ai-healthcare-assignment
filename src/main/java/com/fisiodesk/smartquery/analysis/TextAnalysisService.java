package com.fisiodesk.smartquery.analysis;

import com.fisiodesk.smartquery.config.AiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Picks the analyzer: LLM when enabled, lexicon otherwise or on any failure. */
@Service
public class TextAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TextAnalysisService.class);

    private final HeuristicClinicalTextAnalyzer heuristicAnalyzer;
    private final LlmClinicalTextAnalyzer llmAnalyzer;
    private final AiProperties aiProperties;

    public TextAnalysisService(HeuristicClinicalTextAnalyzer heuristicAnalyzer,
                               LlmClinicalTextAnalyzer llmAnalyzer,
                               AiProperties aiProperties) {
        this.heuristicAnalyzer = heuristicAnalyzer;
        this.llmAnalyzer = llmAnalyzer;
        this.aiProperties = aiProperties;
    }

    public ClinicalNoteAnalysis analyze(String text) {
        ClinicalNoteAnalysis lexical = heuristicAnalyzer.analyze(text);
        if (!aiProperties.enabled()) {
            return lexical;
        }
        try {
            return merge(llmAnalyzer.analyze(text), lexical);
        } catch (RuntimeException e) {
            log.warn("Falling back to heuristic analyzer: {}", e.getMessage());
            return lexical;
        }
    }

    /**
     * Areas: the lexicon wins when it found any (small models tend to list
     * every enum value). Trend: the model wins unless the lexicon found
     * explicit markers of the opposite sign ("situazione stazionaria" must not
     * be neutralised). Evidence follows whoever decided the score.
     */
    static ClinicalNoteAnalysis merge(ClinicalNoteAnalysis llm, ClinicalNoteAnalysis lexical) {
        Set<BodyArea> areas = lexical.bodyAreas().isEmpty() ? llm.bodyAreas() : lexical.bodyAreas();

        boolean lexiconDisagrees = lexical.improvementScore() != 0
                && Integer.signum(lexical.improvementScore()) != Integer.signum(llm.improvementScore());
        if (lexiconDisagrees) {
            return new ClinicalNoteAnalysis(areas, lexical.improvementScore(), lexical.evidence(), llm.analyzer());
        }
        return new ClinicalNoteAnalysis(areas, llm.improvementScore(), llm.evidence(), llm.analyzer());
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
