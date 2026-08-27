package com.fisiodesk.smartquery.analysis;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Italian physiotherapy lexicon shared by the heuristic analyzer and the
 * query planner. Terms are matched on accent-stripped lowercase text and are
 * mostly stems ("miglior" covers migliora/miglioramento/migliorata).
 *
 * The dataset intentionally uses many synonyms for the same concept
 * ("lombalgia", "mal di schiena", "low back pain", "colpo della strega"...):
 * keeping the mapping in one place makes it cheap to extend and easy to test.
 */
public final class ClinicalLexicon {

    private ClinicalLexicon() {
    }

    public static final Map<BodyArea, List<String>> BODY_AREA_TERMS = new LinkedHashMap<>() {{
        put(BodyArea.LUMBAR, List.of(
                "lombalg", "lombar", "bassa schiena", "mal di schiena", "schiena",
                "low back", "colpo della strega", "sciatalg", "lombosacral"));
        put(BodyArea.CERVICAL, List.of(
                "cervical", "collo", "torcicollo", "nucale"));
        put(BodyArea.SHOULDER, List.of(
                "spalla", "cuffia dei rotatori", "periartrite"));
        put(BodyArea.KNEE, List.of(
                "ginocchio", "rotule", "menisc"));
        put(BodyArea.HIP, List.of(
                "anca", "coxalg", "trocanter"));
    }};

    /**
     * Explicit multi-word phrases checked before the generic positive stems,
     * because a naive stem match would misread them ("nessun dolore" is good
     * news even though "nessun" usually negates).
     */
    public static final List<String> POSITIVE_PHRASES = List.of(
            "senza dolore", "nessun dolore", "non ha piu dolore", "senza limitazioni",
            "senza difficolta", "senza problemi");

    /** Phrases that negate an improvement even though they contain a positive stem. */
    public static final List<String> NEGATED_IMPROVEMENT_PHRASES = List.of(
            "non migliora", "nessun miglioramento", "senza miglioramento",
            "non risponde", "mancato miglioramento", "non recupera");

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
