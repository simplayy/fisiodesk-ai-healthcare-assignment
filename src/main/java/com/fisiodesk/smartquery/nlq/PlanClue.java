package com.fisiodesk.smartquery.nlq;

/**
 * A word of the question that the lexicon recognised and what it was read as,
 * e.g. ("bodyAreas", "dolore lombare", "LUMBAR"). Shown to the user so the
 * interpretation of the question is never a black box.
 */
public record PlanClue(String field, String term, String value) {
}
