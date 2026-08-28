package com.fisiodesk.smartquery.analysis;

/** Analyzes one free-text clinical note (lexicon-based or LLM-based). */
public interface ClinicalTextAnalyzer {

    ClinicalNoteAnalysis analyze(String text);

    String name();
}
