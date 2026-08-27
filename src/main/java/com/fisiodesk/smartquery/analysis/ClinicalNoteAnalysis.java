package com.fisiodesk.smartquery.analysis;

import java.util.List;
import java.util.Set;

/**
 * Result of analyzing one free-text clinical note.
 *
 * @param bodyAreas        anatomical areas the note talks about
 * @param improvementScore < 0 worsening/stationary, 0 no clear signal, > 0 improving
 * @param evidence         terms or phrases that triggered the classification
 * @param analyzer         which analyzer produced the result ("heuristic" or "llm")
 */
public record ClinicalNoteAnalysis(
        Set<BodyArea> bodyAreas,
        int improvementScore,
        List<String> evidence,
        String analyzer) {
}
