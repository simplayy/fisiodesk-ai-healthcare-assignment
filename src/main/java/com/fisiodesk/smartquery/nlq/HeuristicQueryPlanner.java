package com.fisiodesk.smartquery.nlq;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.analysis.ClinicalLexicon;
import java.util.EnumSet;
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

    @Override
    public QueryPlan plan(String question) {
        String q = ClinicalLexicon.normalize(question);

        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        ClinicalLexicon.BODY_AREA_TERMS.forEach((area, terms) -> {
            for (String term : terms) {
                if (q.contains(term)) {
                    areas.add(area);
                    break;
                }
            }
        });

        boolean worsening = NEGATED_IMPROVEMENT_PATTERN.matcher(q).find()
                || containsAny(q, "peggior", "stazionar", "non rispond");
        boolean improvement = !worsening && containsAny(q,
                "miglior", "progress", "recuper", "guarit", "sta meglio", "stanno meglio");

        boolean missedLast = containsAny(q,
                "saltato", "saltata", "no show", "no-show", "no_show",
                "non si e presentato", "non si sono presentati", "non presentato",
                "mancato appuntamento", "assente all'ultimo", "assenti all'ultimo");

        int window = QueryPlan.DEFAULT_WINDOW_MONTHS;
        Matcher m = WINDOW_PATTERN.matcher(q);
        if (m.find()) {
            window = Math.max(1, Math.min(24, Integer.parseInt(m.group(1))));
        } else if (WINDOW_SINGLE_PATTERN.matcher(q).find()) {
            window = 1;
        }

        return new QueryPlan(areas, improvement, worsening, window, missedLast, NAME);
    }

    @Override
    public String name() {
        return NAME;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
