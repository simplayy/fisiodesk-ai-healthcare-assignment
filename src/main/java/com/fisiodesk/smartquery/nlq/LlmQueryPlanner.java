package com.fisiodesk.smartquery.nlq;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fisiodesk.smartquery.analysis.BodyArea;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

/** Turns a question into a {@link QueryPlan} with the LLM; output constrained to the JSON schema of {@link Extraction}. */
@Component
public class LlmQueryPlanner implements QueryPlanner {

    public static final String NAME = "llm";

    private static final String SYSTEM_PROMPT = """
            Traduci la domanda di un professionista sanitario in un filtro strutturato.
            "dolore lombare", "mal di schiena", "lombalgia", "low back pain",
            "colpo della strega" indicano tutti l'area LUMBAR.
            Pazienti migliorati, guariti, recuperati, con progressi, che stanno meglio:
            requireImprovement=true.
            Pazienti peggiorati, non migliorati, senza miglioramento, stazionari:
            requireWorsening=true e requireImprovement=false.

            Esempi:
            "Pazienti con dolore lombare migliorati negli ultimi 3 mesi che hanno
            saltato l'ultimo appuntamento" ->
            {"bodyAreas":["LUMBAR"],"requireImprovement":true,"requireWorsening":false,
             "windowMonths":3,"missedLastAppointment":true}
            "Pazienti con lombalgia che non mostrano miglioramento negli ultimi 6 mesi" ->
            {"bodyAreas":["LUMBAR"],"requireImprovement":false,"requireWorsening":true,
             "windowMonths":6,"missedLastAppointment":false}
            "Pazienti con cervicalgia guariti negli ultimi 2 mesi" ->
            {"bodyAreas":["CERVICAL"],"requireImprovement":true,"requireWorsening":false,
             "windowMonths":2,"missedLastAppointment":false}
            "Chi ha saltato l'ultimo appuntamento?" ->
            {"bodyAreas":[],"requireImprovement":false,"requireWorsening":false,
             "windowMonths":3,"missedLastAppointment":true,"attendedLastAppointment":false}
            "Pazienti con lombalgia migliorati che non hanno saltato l'ultimo appuntamento" ->
            {"bodyAreas":["LUMBAR"],"requireImprovement":true,"requireWorsening":false,
             "windowMonths":3,"missedLastAppointment":false,"attendedLastAppointment":true}
            """;

    /** Descriptions end up in the JSON schema. */
    record Extraction(
            @JsonPropertyDescription("Aree anatomiche citate nella domanda, vuoto se nessuna")
            List<BodyArea> bodyAreas,
            @JsonPropertyDescription("true se si chiedono pazienti migliorati/guariti")
            boolean requireImprovement,
            @JsonPropertyDescription("true se si chiedono pazienti peggiorati o non migliorati")
            boolean requireWorsening,
            @JsonPropertyDescription("Finestra temporale in mesi citata nella domanda, 3 se assente")
            int windowMonths,
            @JsonPropertyDescription("true se si chiede chi ha saltato l'ultimo appuntamento")
            boolean missedLastAppointment,
            @JsonPropertyDescription("true se si chiede il contrario, cioe chi NON ha saltato l'ultimo appuntamento")
            boolean attendedLastAppointment) {
    }

    private final ChatClient chatClient;
    private final BeanOutputConverter<Extraction> converter = new BeanOutputConverter<>(Extraction.class);
    private final OpenAiChatOptions options;

    public LlmQueryPlanner(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.options = OpenAiChatOptions.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(converter.getJsonSchema())
                        .build())
                .build();
    }

    @Override
    public QueryPlan plan(String question) {
        Extraction extraction = chatClient.prompt()
                .options(options)
                .system(SYSTEM_PROMPT)
                .user("Domanda:\n" + question)
                .call()
                .entity(converter);
        if (extraction == null) {
            throw new IllegalStateException("Empty plan from the model");
        }
        Set<BodyArea> areas = EnumSet.noneOf(BodyArea.class);
        if (extraction.bodyAreas() != null) {
            areas.addAll(extraction.bodyAreas());
        }
        int window = extraction.windowMonths() <= 0
                ? QueryPlan.DEFAULT_WINDOW_MONTHS : Math.min(24, extraction.windowMonths());
        boolean attended = extraction.attendedLastAppointment();
        return new QueryPlan(areas, extraction.requireImprovement(), extraction.requireWorsening(),
                window, extraction.missedLastAppointment() && !attended, attended, NAME);
    }
}
