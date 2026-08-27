package com.fisiodesk.smartquery.analysis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic, zero-cost analyzer based on the physiotherapy lexicon.
 *
 * It is intentionally simple but handles the traps present in real clinical
 * notes: negations ("non migliora", "non risponde bene alla terapia") must not
 * count as improvement, and phrases like "nessun dolore residuo" must not
 * count as negations. It doubles as the always-available fallback when the
 * LLM analyzer is disabled, over budget or failing.
 */
@Component
public class HeuristicClinicalTextAnalyzer implements ClinicalTextAnalyzer {

    public static final String NAME = "heuristic";

    private static final int NEGATION_WINDOW = 12;

    @Override
    public ClinicalNoteAnalysis analyze(String text) {
        String normalized = ClinicalLexicon.normalize(text);

        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        List<String> evidence = new ArrayList<>();

        ClinicalLexicon.BODY_AREA_TERMS.forEach((area, terms) -> {
            for (String term : terms) {
                if (normalized.contains(term)) {
                    areas.add(area);
                    evidence.add("area:" + term.strip());
                    break;
                }
            }
        });

        int score = 0;

        // Explicitly negated improvements first: "non migliora" must win over "migliora".
        for (String phrase : ClinicalLexicon.NEGATED_IMPROVEMENT_PHRASES) {
            if (normalized.contains(phrase)) {
                score--;
                evidence.add("-" + phrase);
            }
        }

        for (String term : ClinicalLexicon.NEGATIVE_TERMS) {
            if (normalized.contains(term)) {
                score--;
                evidence.add("-" + term);
            }
        }

        for (String phrase : ClinicalLexicon.POSITIVE_PHRASES) {
            if (normalized.contains(phrase)) {
                score++;
                evidence.add("+" + phrase);
            }
        }

        for (String term : ClinicalLexicon.POSITIVE_TERMS) {
            int idx = normalized.indexOf(term);
            while (idx >= 0) {
                if (!isNegated(normalized, idx)) {
                    score++;
                    evidence.add("+" + term);
                    break;
                }
                idx = normalized.indexOf(term, idx + term.length());
            }
        }

        return new ClinicalNoteAnalysis(areas, clamp(score), List.copyOf(evidence), NAME);
    }

    @Override
    public String name() {
        return NAME;
    }

    private boolean isNegated(String text, int matchIndex) {
        int from = Math.max(0, matchIndex - NEGATION_WINDOW);
        String preceding = text.substring(from, matchIndex);
        return ClinicalLexicon.NEGATION_MARKERS.stream().anyMatch(preceding::contains);
    }

    private int clamp(int score) {
        return Math.max(-3, Math.min(3, score));
    }
}
