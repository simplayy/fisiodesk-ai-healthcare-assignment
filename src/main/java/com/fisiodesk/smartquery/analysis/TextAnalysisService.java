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
     * Combines the model's reading with the lexicon's. Anatomical areas are a
     * closed vocabulary where the lexicon is precise and small models are not
     * (they tend to list every enum value): the model's areas only count when
     * the lexicon found none. For the clinical trend the model is trusted
     * unless the lexicon found explicit markers pointing the other way or the
     * model saw no signal at all - "situazione stazionaria" or "non migliora"
     * must never be neutralised. Evidence always comes from the model.
     */
    static ClinicalNoteAnalysis merge(ClinicalNoteAnalysis llm, ClinicalNoteAnalysis lexical) {
        Set<BodyArea> areas = lexical.bodyAreas().isEmpty() ? llm.bodyAreas() : lexical.bodyAreas();

        int score = llm.improvementScore();
        boolean lexiconDisagrees = lexical.improvementScore() != 0
                && Integer.signum(lexical.improvementScore()) != Integer.signum(score);
        if (lexiconDisagrees) {
            score = lexical.improvementScore();
        }
        return new ClinicalNoteAnalysis(areas, score, llm.evidence(), llm.analyzer());
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
