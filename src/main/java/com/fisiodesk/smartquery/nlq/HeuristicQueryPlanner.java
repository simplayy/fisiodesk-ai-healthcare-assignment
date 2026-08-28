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
 * Rule-based fallback parser for natural-language questions. It reuses the
 * clinical lexicon to spot anatomical areas and looks for a handful of intent
 * markers (improvement, missed appointment, time window). Deliberately
 * conservative: anything it does not recognize is simply not filtered on,
 * which for a clinical tool beats guessing.
 */
@Component
public class HeuristicQueryPlanner implements QueryPlanner {

    public static final String NAME = "heuristic";

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("ultim[oi]\\s+(\\d+)\\s+mes[ei]");
    private static final Pattern WINDOW_SINGLE_PATTERN =
            Pattern.compile("ultimo\\s+mese");

    /** Catches "non migliora", "non mostrano miglioramento", "nessun miglioramento"... */
    private static final Pattern NEGATED_IMPROVEMENT_PATTERN =
            Pattern.compile("(non|senza|nessun)\\s+(\\w+\\s+){0,2}miglior");

    private record Analysis(QueryPlan plan, List<PlanClue> clues) {
    }

    @Override
    public QueryPlan plan(String question) {
        return analyze(question).plan();
    }

    /** The words of the question the lexicon recognised, and how it read them. */
    public List<PlanClue> explain(String question) {
        return analyze(question).clues();
    }

    private Analysis analyze(String question) {
        String q = ClinicalLexicon.normalize(question);
        List<PlanClue> clues = new ArrayList<>();

        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        ClinicalLexicon.BODY_AREA_TERMS.forEach((area, terms) -> {
            for (String term : terms) {
                if (q.contains(term)) {
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

        String missedTerm = firstMatch(q,
                "saltato", "saltata", "no show", "no-show", "no_show",
                "non si e presentato", "non si sono presentati", "non presentato",
                "mancato appuntamento", "assente all'ultimo", "assenti all'ultimo");
        boolean missedLast = missedTerm != null;
        if (missedLast) {
            clues.add(new PlanClue("missedLastAppointment", snippet(q, missedTerm), "true"));
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

        return new Analysis(new QueryPlan(areas, improvement, worsening, window, missedLast, NAME), clues);
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

    @Override
    public String name() {
        return NAME;
    }

}
