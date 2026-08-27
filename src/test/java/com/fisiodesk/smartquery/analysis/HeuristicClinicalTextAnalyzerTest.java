package com.fisiodesk.smartquery.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sentences below come from the demo dataset and cover the linguistic
 * traps that motivated the analyzer design: synonyms for the same condition
 * and negations that contain positive words.
 */
class HeuristicClinicalTextAnalyzerTest {

    private HeuristicClinicalTextAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new HeuristicClinicalTextAnalyzer();
    }

    @Test
    void recognizesLumbarSynonyms() {
        assertThat(analyzer.analyze("Paziente con lombalgia acuta").bodyAreas())
                .contains(BodyArea.LUMBAR);
        assertThat(analyzer.analyze("Forte low back pain con irradiazione glutea").bodyAreas())
                .contains(BodyArea.LUMBAR);
        assertThat(analyzer.analyze("Riferisce 'colpo della strega' mentre sollevava un peso").bodyAreas())
                .contains(BodyArea.LUMBAR);
        assertThat(analyzer.analyze("Dolore alla bassa schiena da tre mesi").bodyAreas())
                .contains(BodyArea.LUMBAR);
    }

    @Test
    void cervicalNotesAreNotTaggedAsLumbar() {
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Cervicalgia acuta con limitazione nei movimenti del collo. Dolore 7/10.");
        assertThat(analysis.bodyAreas()).containsExactly(BodyArea.CERVICAL);
    }

    @Test
    void detectsClearImprovement() {
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Paziente riferisce miglioramento significativo del dolore lombare. "
                + "Scala del dolore da 8/10 a 3/10.");
        assertThat(analysis.improvementScore()).isPositive();
    }

    @Test
    void negatedImprovementIsNotImprovement() {
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Situazione complicata. Il mal di schiena non migliora nonostante le terapie.");
        assertThat(analysis.improvementScore()).isNegative();
    }

    @Test
    void negatedPositiveResponseIsNotImprovement() {
        // "risponde bene" is positive, but here it is preceded by "non".
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Seduta difficile. La paziente non risponde bene alla terapia. "
                + "Dolore lombare persistente.");
        assertThat(analysis.improvementScore()).isNegative();
    }

    @Test
    void stationarySituationIsNotImprovement() {
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Situazione stazionaria. La lombalgia persiste con intensita' invariata.");
        assertThat(analysis.improvementScore()).isNegative();
    }

    @Test
    void painFreePhrasesCountAsImprovementDespiteNegationWords() {
        // "nessun" and "senza" are negation markers, but "nessun dolore" is good news.
        assertThat(analyzer.analyze("Nessun dolore residuo alla zona lombare.")
                .improvementScore()).isPositive();
        assertThat(analyzer.analyze("Riesce a piegarsi senza dolore acuto alla schiena.")
                .improvementScore()).isPositive();
    }

    @Test
    void neutralFirstAssessmentHasNoSignal() {
        ClinicalNoteAnalysis analysis = analyzer.analyze(
                "Prima valutazione: paziente lamenta forte dolore alla bassa schiena "
                + "da circa 3 mesi. Rigidita' mattutina presente.");
        assertThat(analysis.improvementScore()).isZero();
    }
}
