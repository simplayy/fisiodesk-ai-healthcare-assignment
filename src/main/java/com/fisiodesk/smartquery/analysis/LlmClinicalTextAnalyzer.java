package com.fisiodesk.smartquery.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisiodesk.smartquery.llm.LlmClient;
import com.fisiodesk.smartquery.llm.LlmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * LLM-backed note analyzer. Used only at indexing time (never in the query
 * hot path) and only for notes not already analyzed, so the number of API
 * calls is bounded by the number of new/edited notes - that is how the AI
 * budget stays under control.
 */
@Component
public class LlmClinicalTextAnalyzer implements ClinicalTextAnalyzer {

    public static final String NAME = "llm";

    private static final String SYSTEM_PROMPT = """
            Sei un assistente che analizza note cliniche di fisioterapia in italiano.
            Rispondi SOLO con un oggetto JSON con questi campi:
            - "body_areas": array di aree anatomiche trattate nella nota, tra:
              LUMBAR (zona lombare/schiena bassa), CERVICAL (collo/cervicale),
              SHOULDER (spalla), KNEE (ginocchio), HIP (anca), OTHER.
            - "improvement": intero da -3 a 3. Positivo se la nota indica un
              miglioramento clinico del paziente, negativo se indica peggioramento
              o situazione stazionaria, 0 se non c'e' un segnale chiaro.
              Attenzione alle negazioni ("non migliora", "non risponde alla terapia").
            - "evidence": array di brevi frasi della nota che giustificano la valutazione.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmClinicalTextAnalyzer(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ClinicalNoteAnalysis analyze(String text) {
        String raw = llmClient.complete(SYSTEM_PROMPT, "Nota clinica:\n" + text);
        try {
            JsonNode root = objectMapper.readTree(raw);
            Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
            for (JsonNode area : root.path("body_areas")) {
                try {
                    areas.add(BodyArea.valueOf(area.asText().trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown label from the model: skip rather than fail the note.
                }
            }
            int improvement = Math.max(-3, Math.min(3, root.path("improvement").asInt(0)));
            List<String> evidence = new ArrayList<>();
            root.path("evidence").forEach(node -> evidence.add(node.asText()));
            return new ClinicalNoteAnalysis(areas, improvement, evidence, NAME);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Unparseable LLM analysis: " + raw, e);
        }
    }

    @Override
    public String name() {
        return NAME;
    }
}
