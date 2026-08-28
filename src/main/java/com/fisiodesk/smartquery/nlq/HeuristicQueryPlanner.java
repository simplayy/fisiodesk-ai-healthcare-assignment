package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.analysis.ClinicalLexicon;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rule-based question parser, also the fallback when the LLM is off or late.
 * Uses the lexicon for areas and a few markers for trend, no-show and time
 * window; only filters on what it recognizes.
 */
@Component
public class HeuristicQueryPlanner implements QueryPlanner {

    public static final String NAME = "heuristic";

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("ultim[oi]\\s+(\\d+)\\s+mes[ei]");
    private static final Pattern WINDOW_SINGLE_PATTERN =
            Pattern.compile("ultimo\\s+mese");

    /**
     * "non migliora", "non hanno fatto progressi", "nessun segno di recupero":
     * up to three words in between, over the same stems the lexicon reads as
     * positive, otherwise a negation on one of them would be missed.
     */
    private static final Pattern NEGATED_IMPROVEMENT_PATTERN =
            Pattern.compile("(non|senza|nessun\\w*)\\s+(\\w+\\s+){0,3}(miglior|progress|recuper|guarit)");

    /** "che non hanno saltato l'ultimo appuntamento": the opposite filter, not the same one. */
    private static final Pattern NEGATED_MISSED_PATTERN =
            Pattern.compile("(non|senza|nessun\\w*)\\s+(\\w+\\s+){0,3}(saltat|mancat|no.show)");

    private record Analysis(QueryPlan plan, List<PlanClue> clues) {
    }

    @Override
    public QueryPlan plan(String question) {
        return analyze(question).plan();
    }

    /** Words of the question the lexicon recognised and how it read them. */
    public List<PlanClue> explain(String question) {
        return analyze(question).clues();
    }

    private Analysis analyze(String question) {
        String q = ClinicalLexicon.normalize(question);
        List<PlanClue> clues = new ArrayList<>();

        String forAreas = ClinicalLexicon.disambiguate(q);
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        ClinicalLexicon.BODY_AREA_TERMS.forEach((area, terms) -> {
            for (String term : terms) {
                if (ClinicalLexicon.containsTerm(forAreas, term)) {
                    areas.add(area);
                    clues.add(new PlanClue("bodyAreas", snippet(q, term), area.name()));
                    break;
                }
            }
        });

        Matcher negated = NEGATED_IMPROVEMENT_PATTERN.matcher(q);
        boolean worsening = false;
        if (negated.find()) {
            worsening = true;
            clues.add(new PlanClue("requireWorsening", snippet(q, negated.group()), "true"));
        } else {
            String term = firstMatch(q, "peggior", "stazionar", "non rispond");
            if (term != null) {
                worsening = true;
                clues.add(new PlanClue("requireWorsening", snippet(q, term), "true"));
            }
        }
        boolean improvement = false;
        if (!worsening) {
            String term = firstMatch(q, "miglior", "progress", "recuper", "guarit", "sta meglio", "stanno meglio");
            if (term != null) {
                improvement = true;
                clues.add(new PlanClue("requireImprovement", snippet(q, term), "true"));
            }
        }

        // Check the negation first: "non hanno saltato" contains "saltato".
        Matcher negatedMissed = NEGATED_MISSED_PATTERN.matcher(q);
        boolean attendedLast = false;
        boolean missedLast = false;
        if (negatedMissed.find()) {
            attendedLast = true;
            clues.add(new PlanClue("attendedLastAppointment", snippet(q, negatedMissed.group()), "true"));
        } else {
            String attendedTerm = firstMatch(q, "si sono presentati", "si e presentato", "si e presentata",
                    "hanno rispettato l'appuntamento", "sono venuti all'ultimo");
            if (attendedTerm != null) {
                attendedLast = true;
                clues.add(new PlanClue("attendedLastAppointment", snippet(q, attendedTerm), "true"));
            } else {
                String missedTerm = firstMatch(q,
                        "saltato", "saltata", "no show", "no-show", "no_show",
                        "non si e presentato", "non si sono presentati", "non presentato",
                        "mancato appuntamento", "assente all'ultimo", "assenti all'ultimo");
                missedLast = missedTerm != null;
                if (missedLast) {
                    clues.add(new PlanClue("missedLastAppointment", snippet(q, missedTerm), "true"));
                }
            }
        }

        int window = QueryPlan.DEFAULT_WINDOW_MONTHS;
        Matcher m = WINDOW_PATTERN.matcher(q);
        if (m.find()) {
            window = Math.max(1, Math.min(24, Integer.parseInt(m.group(1))));
            clues.add(new PlanClue("windowMonths", m.group(), String.valueOf(window)));
        } else {
            Matcher single = WINDOW_SINGLE_PATTERN.matcher(q);
            if (single.find()) {
                window = 1;
                clues.add(new PlanClue("windowMonths", single.group(), "1"));
            }
        }

        return new Analysis(new QueryPlan(areas, improvement, worsening, window, missedLast, attendedLast, NAME), clues);
    }

    /** The matched term with the word it belongs to ("lombar" -> "lombare"). */
    private static String snippet(String q, String term) {
        int start = q.indexOf(term);
        if (start < 0) {
            return term;
        }
        int end = start + term.length();
        while (end < q.length() && Character.isLetter(q.charAt(end))) {
            end++;
        }
        while (start > 0 && Character.isLetter(q.charAt(start - 1))) {
            start--;
        }
        return q.substring(start, end);
    }

    private static String firstMatch(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return term;
            }
        }
        return null;
    }
}
