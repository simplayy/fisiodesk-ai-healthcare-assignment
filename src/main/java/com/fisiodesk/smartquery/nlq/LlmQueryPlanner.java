package com.fisiodesk.smartquery.nlq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.llm.LlmClient;
import com.fisiodesk.smartquery.llm.LlmException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Translates the professional's question into a {@link QueryPlan} using the
 * LLM. This is the only per-query LLM call in the system, on a very short
 * prompt; the notes themselves are never sent at query time.
 */
@Component
public class LlmQueryPlanner implements QueryPlanner {

    public static final String NAME = "llm";

    private static final String SYSTEM_PROMPT = """
            Traduci la domanda di un professionista sanitario in un filtro strutturato.
            Rispondi SOLO con un oggetto JSON con questi campi:
            - "body_areas": array tra LUMBAR, CERVICAL, SHOULDER, KNEE, HIP, OTHER
              (vuoto se la domanda non cita una zona anatomica).
              Nota: "dolore lombare", "mal di schiena", "lombalgia", "low back pain",
              "colpo della strega" indicano tutti LUMBAR.
            - "require_improvement": true se la domanda chiede pazienti che sono migliorati.
            - "require_worsening": true se chiede pazienti peggiorati o non migliorati.
            - "window_months": finestra temporale in mesi citata nella domanda (default 3).
            - "missed_last_appointment": true se la domanda chiede chi ha saltato
              l'ultimo appuntamento / non si e' presentato.

            Fai molta attenzione alle negazioni: "non mostrano miglioramento",
            "non migliorati", "senza miglioramento" significano require_worsening=true
            e require_improvement=false.

            Esempi:
            "Pazienti con dolore lombare migliorati negli ultimi 3 mesi che hanno
            saltato l'ultimo appuntamento" ->
            {"body_areas":["LUMBAR"],"require_improvement":true,"require_worsening":false,
             "window_months":3,"missed_last_appointment":true}
            "Pazienti con lombalgia che non mostrano miglioramento negli ultimi 6 mesi" ->
            {"body_areas":["LUMBAR"],"require_improvement":false,"require_worsening":true,
             "window_months":6,"missed_last_appointment":false}
            "Chi ha saltato l'ultimo appuntamento?" ->
            {"body_areas":[],"require_improvement":false,"require_worsening":false,
             "window_months":3,"missed_last_appointment":true}
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmQueryPlanner(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public QueryPlan plan(String question) {
        String raw = llmClient.complete(SYSTEM_PROMPT, "Domanda:\n" + question);
        try {
            JsonNode root = objectMapper.readTree(raw);
            Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
            for (JsonNode area : root.path("body_areas")) {
                try {
                    areas.add(BodyArea.valueOf(area.asText().trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // skip unknown labels
                }
            }
            int window = root.path("window_months").asInt(QueryPlan.DEFAULT_WINDOW_MONTHS);
            return new QueryPlan(
                    areas,
                    root.path("require_improvement").asBoolean(false),
                    root.path("require_worsening").asBoolean(false),
                    Math.max(1, Math.min(24, window)),
                    root.path("missed_last_appointment").asBoolean(false),
                    NAME);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Unparseable LLM plan: " + raw, e);
        }
    }

    @Override
    public String name() {
        return NAME;
    }
}
