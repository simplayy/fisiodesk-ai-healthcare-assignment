package com.fisiodesk.smartquery.analysis;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Italian physiotherapy terms used by the heuristic analyzer and the rule-based
 * planner. Matched on lowercase, accent-stripped text; most entries are stems
 * ("miglior" covers migliora, miglioramento, migliorata).
 */
public final class ClinicalLexicon {

    private ClinicalLexicon() {
    }

    public static final Map<BodyArea, List<String>> BODY_AREA_TERMS = new LinkedHashMap<>() {{
        put(BodyArea.LUMBAR, List.of(
                "lombalg", "lombar", "bassa schiena", "mal di schiena", "schiena",
                "low back", "colpo della strega", "sciatalg", "lombosacral"));
        put(BodyArea.CERVICAL, List.of(
                "cervical", "collo", "torcicollo", "nucal"));
        put(BodyArea.SHOULDER, List.of(
                "spall", "cuffia dei rotatori", "periartrite"));
        put(BodyArea.KNEE, List.of(
                "ginocchi", "rotul", "menisc"));
        put(BodyArea.HIP, List.of(
                "anca", "coxalg", "trocanter"));
    }};

    /** Checked before the positive stems: "nessun dolore" is good news even though "nessun" negates. */
    public static final List<String> POSITIVE_PHRASES = List.of(
            "senza dolore", "nessun dolore", "non ha piu dolore", "senza limitazioni",
            "senza difficolta", "senza problemi");

    /**
     * Phrases that negate an improvement even though they contain a positive stem.
     * "non risponde" carries its object: on its own it also matches "non risponde
     * al telefono", which says nothing about how the therapy is going.
     */
    public static final List<String> NEGATED_IMPROVEMENT_PHRASES = List.of(
            "non migliora", "nessun miglioramento", "senza miglioramento",
            "non risponde bene", "non risponde alla terap", "non risponde al trattamento",
            "non risponde alle terap", "non risponde alle cure",
            "mancato miglioramento", "non recupera");

    public static final List<String> POSITIVE_TERMS = List.of(
            "miglior", "progress", "recuper", "risoluz", "risolt", "guarit",
            "guarigion", "sollievo", "diminuit", "diminuisc", "ridott", "riduzion",
            "meglio", "benissimo", "eccellente", "ottim", "buona risposta",
            "risponde bene", "sopportabile", "in remissione", "attenuat");

    public static final List<String> NEGATIVE_TERMS = List.of(
            "peggior", "stazionar", "persist", "invariat", "complicat",
            "scoraggiat", "aggravat", "recidiv", "riacutizz", "sofferente",
            "molto dolorante", "non collaborat");

    /** Words that flip the meaning of a positive term appearing shortly after them. */
    public static final List<String> NEGATION_MARKERS = List.of(
            "non ", "nessun", "senza ", "scarso ", "scarsa ", "assenza di", "mancato ");

    /**
     * Phrases that contain the term of another area: "collo del femore" is a hip,
     * not a neck, and the upper back is not the lower back. Rewritten before
     * matching, so each lands on the right area or on none when the taxonomy has
     * no value for it (the thoracic spine).
     */
    private static final Map<String, String> AMBIGUOUS_PHRASES = new LinkedHashMap<>() {{
        put("collo del femore", "anca");
        put("collo dell'omero", "spalla");
        put("collo dell omero", "spalla");
        put("collo del piede", "caviglia");
        put("parte alta della schiena", "dorso");
        put("schiena alta", "dorso");
        put("zona dorsale", "dorso");
        put("colonna dorsale", "dorso");
    }};

    /** Applies the rewrites above; call before matching body areas. */
    public static String disambiguate(String normalizedText) {
        String text = normalizedText;
        for (Map.Entry<String, String> entry : AMBIGUOUS_PHRASES.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    /** Term at the start of a word: "anca" must not match "stanca". */
    public static boolean containsTerm(String normalizedText, String term) {
        int from = 0;
        while (true) {
            int i = normalizedText.indexOf(term, from);
            if (i < 0) {
                return false;
            }
            if (i == 0 || !Character.isLetter(normalizedText.charAt(i - 1))) {
                return true;
            }
            from = i + 1;
        }
    }

    /** Lowercases and strips accents so "mobilità" matches "mobilita". */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase();
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }
}
