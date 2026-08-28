package com.fisiodesk.smartquery.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fisiodesk.smartquery.config.AiProperties;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TextAnalysisServiceTest {

    private static final String NOTE =
            "Situazione eccellente. Completa risoluzione della lombalgia, sport senza limitazioni.";

    @Mock
    private LlmClinicalTextAnalyzer llmAnalyzer;

    @Test
    void lexiconAreasOverrideModelAreasWhileTrendComesFromTheModel() {
        when(llmAnalyzer.analyze(anyString())).thenReturn(new ClinicalNoteAnalysis(
                EnumSet.allOf(BodyArea.class), 3, List.of("Situazione eccellente"), "llm"));

        ClinicalNoteAnalysis analysis = service(true).analyze(NOTE);

        assertThat(analysis.bodyAreas()).containsExactly(BodyArea.LUMBAR);
        assertThat(analysis.improvementScore()).isEqualTo(3);
        assertThat(analysis.evidence()).containsExactly("Situazione eccellente");
        assertThat(analysis.analyzer()).isEqualTo("llm");
    }

    @Test
    void explicitNegativeMarkersAreNotNeutralisedByTheModel() {
        // The model returned "no signal" for a note that literally says the situation is stationary.
        when(llmAnalyzer.analyze(anyString())).thenReturn(new ClinicalNoteAnalysis(
                EnumSet.of(BodyArea.LUMBAR), 0, List.of(), "llm"));

        ClinicalNoteAnalysis analysis = service(true).analyze(
                "Situazione stazionaria. La lombalgia persiste con intensita' invariata.");

        assertThat(analysis.improvementScore()).isNegative();
        assertThat(analysis.analyzer()).isEqualTo("llm");
    }

    @Test
    void modelTrendIsKeptWhenTheLexiconAgreesOrIsSilent() {
        when(llmAnalyzer.analyze(anyString())).thenReturn(new ClinicalNoteAnalysis(
                EnumSet.of(BodyArea.LUMBAR), 1, List.of(), "llm"));

        assertThat(service(true).analyze(NOTE).improvementScore()).isEqualTo(1);
        assertThat(service(true).analyze("Paziente teso ma collaborativo, zona lombare.")
                .improvementScore()).isEqualTo(1);
    }

    @Test
    void modelAreasAreKeptWhenTheLexiconFindsNone() {
        when(llmAnalyzer.analyze(anyString())).thenReturn(new ClinicalNoteAnalysis(
                EnumSet.of(BodyArea.HIP), 1, List.of(), "llm"));

        ClinicalNoteAnalysis analysis = service(true).analyze("Dolore in sede coxofemorale in lieve riduzione.");

        assertThat(analysis.bodyAreas()).containsExactly(BodyArea.HIP);
    }

    @Test
    void modelFailureFallsBackToTheLexicon() {
        when(llmAnalyzer.analyze(anyString())).thenThrow(new IllegalStateException("provider down"));

        ClinicalNoteAnalysis analysis = service(true).analyze(NOTE);

        assertThat(analysis.analyzer()).isEqualTo("heuristic");
        assertThat(analysis.bodyAreas()).containsExactly(BodyArea.LUMBAR);
        assertThat(analysis.improvementScore()).isPositive();
    }

    @Test
    void disabledModelUsesTheLexiconOnly() {
        ClinicalNoteAnalysis analysis = service(false).analyze(NOTE);

        assertThat(analysis.analyzer()).isEqualTo("heuristic");
    }

    private TextAnalysisService service(boolean aiEnabled) {
        return new TextAnalysisService(new HeuristicClinicalTextAnalyzer(), llmAnalyzer,
                new AiProperties(aiEnabled, 1500));
    }
}
