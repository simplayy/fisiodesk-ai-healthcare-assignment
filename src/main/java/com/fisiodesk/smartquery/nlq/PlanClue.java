package com.fisiodesk.smartquery.nlq;

/** A word of the question the lexicon recognised and what it was read as, e.g. ("bodyAreas", "lombare", "LUMBAR"). */
public record PlanClue(String field, String term, String value) {
}
