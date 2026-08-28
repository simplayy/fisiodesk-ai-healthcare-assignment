package com.fisiodesk.smartquery.analysis;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** LLM analyzer, used only at indexing time and only for new or edited notes. */
@Component
public class LlmClinicalTextAnalyzer implements ClinicalTextAnalyzer {

    public static final String NAME = "llm";

    private static final String SYSTEM_PROMPT = """
            Analizza una nota clinica di fisioterapia in italiano.
            Indica le aree anatomiche trattate e se la nota descrive un miglioramento
            clinico del paziente (valore positivo), un peggioramento o una situazione
            stazionaria (valore negativo) oppure nessun segnale chiaro (0).
            Attenzione alle negazioni: "non migliora", "non risponde alla terapia"
            sono segnali negativi. Elenca solo le aree effettivamente citate nella
            nota (di solito una).

            Esempi:
            "Netto miglioramento della lombalgia, il paziente cammina senza dolore." ->
            {"bodyAreas":["LUMBAR"],"improvement":2,"evidence":["Netto miglioramento della lombalgia","cammina senza dolore"]}
            "Situazione stazionaria, la cervicalgia persiste. Il paziente non risponde alla terapia." ->
            {"bodyAreas":["CERVICAL"],"improvement":-2,"evidence":["Situazione stazionaria","non risponde alla terapia"]}
            """;

    record Extraction(
            @JsonPropertyDescription("Aree anatomiche trattate nella nota")
            List<BodyArea> bodyAreas,
            @JsonPropertyDescription("Da -3 (netto peggioramento) a 3 (netto miglioramento), 0 se non chiaro")
            int improvement,
            @JsonPropertyDescription("Brevi frasi della nota che giustificano la valutazione")
            List<String> evidence) {
    }

    private final ChatClient chatClient;
    private final BeanOutputConverter<Extraction> converter = new BeanOutputConverter<>(Extraction.class);
    private final OpenAiChatOptions options;

    /** @param analysisModel optional separate model, so indexing and planning use different provider quotas */
    public LlmClinicalTextAnalyzer(ChatClient chatClient,
                                   @Value("${smartquery.ai.analysis-model:}") String analysisModel) {
        this.chatClient = chatClient;
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(converter.getJsonSchema())
                        .build());
        if (analysisModel != null && !analysisModel.isBlank()) {
            builder.model(analysisModel.trim());
        }
        this.options = builder.build();
    }

    @Override
    public ClinicalNoteAnalysis analyze(String text) {
        Extraction extraction = chatClient.prompt()
                .options(options)
                .system(SYSTEM_PROMPT)
                .user("Nota clinica:\n" + text)
                .call()
                .entity(converter);
        if (extraction == null) {
            throw new IllegalStateException("Empty analysis from the model");
        }
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        if (extraction.bodyAreas() != null) {
            areas.addAll(extraction.bodyAreas());
        }
        int improvement = Math.max(-3, Math.min(3, extraction.improvement()));
        List<String> evidence = extraction.evidence() == null ? List.of() : extraction.evidence();
        return new ClinicalNoteAnalysis(areas, improvement, evidence, NAME);
    }

    @Override
    public String name() {
        return NAME;
    }
}
