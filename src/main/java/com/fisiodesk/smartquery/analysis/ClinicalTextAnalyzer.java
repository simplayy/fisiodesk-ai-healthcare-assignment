package com.fisiodesk.smartquery.analysis;

/**
 * Strategy interface for the note analysis step. Two implementations exist:
 * a deterministic lexicon-based analyzer (always available, zero cost) and an
 * LLM-backed one that is enabled when an API key is configured and falls back
 * to the heuristic on any failure.
 */
public interface ClinicalTextAnalyzer {

    ClinicalNoteAnalysis analyze(String text);

    String name();
}
