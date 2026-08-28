package com.fisiodesk.smartquery.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fisiodesk.smartquery.analysis.BodyArea;
import com.fisiodesk.smartquery.domain.EventoCalendario;
import com.fisiodesk.smartquery.domain.NoteInsight;
import com.fisiodesk.smartquery.domain.Paziente;
import com.fisiodesk.smartquery.nlq.QueryPlan;
import com.fisiodesk.smartquery.query.QueryDtos.PatientResult;
import com.fisiodesk.smartquery.repository.EventoCalendarioRepository;
import com.fisiodesk.smartquery.repository.NoteInsightRepository;
import com.fisiodesk.smartquery.repository.PazienteRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Reproduces in miniature the scenarios of the demo dataset: an improving
 * lumbar patient who skipped the last visit (match), a non-improving lumbar
 * patient (excluded), a cervical patient improving on the wrong condition
 * (excluded) and an improving lumbar patient who showed up (excluded).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartQueryServiceTest {

    private static final Instant REFERENCE = Instant.parse("2024-12-31T23:59:59Z");

    private final ObjectId improvingNoShow = new ObjectId();
    private final ObjectId notImproving = new ObjectId();
    private final ObjectId cervicalOnly = new ObjectId();
    private final ObjectId improvingButPresent = new ObjectId();

    @Mock
    private NoteInsightRepository insightRepository;
    @Mock
    private EventoCalendarioRepository eventoRepository;
    @Mock
    private PazienteRepository pazienteRepository;

    private SmartQueryService service;

    @BeforeEach
    void setUp() {
        service = new SmartQueryService(insightRepository, eventoRepository, pazienteRepository);

        when(insightRepository.findByDataGreaterThanEqualAndDataLessThanEqual(any(), any())).thenReturn(List.of(
                insight(improvingNoShow, "2024-11-15", Set.of("LUMBAR"), 2),
                insight(improvingNoShow, "2024-12-01", Set.of("LUMBAR"), 2),
                insight(notImproving, "2024-10-25", Set.of("LUMBAR"), -3),
                insight(notImproving, "2024-12-10", Set.of("LUMBAR"), -1),
                insight(cervicalOnly, "2024-11-20", Set.of("CERVICAL"), 2),
                insight(improvingButPresent, "2024-12-15", Set.of("LUMBAR"), 3)));

        when(pazienteRepository.findAllById(anyCollection())).thenReturn(List.of(
                patient(improvingNoShow, "Mario", "Rossi"),
                patient(notImproving, "Anna", "Ferrari"),
                patient(cervicalOnly, "Giuseppe", "Verdi"),
                patient(improvingButPresent, "Piero", "Neri")));

        when(eventoRepository.findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
                eq(improvingNoShow), any())).thenReturn(Optional.of(event("no_show")));
        when(eventoRepository.findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
                eq(notImproving), any())).thenReturn(Optional.of(event("no_show")));
        when(eventoRepository.findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
                eq(cervicalOnly), any())).thenReturn(Optional.of(event("prenotato")));
        when(eventoRepository.findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
                eq(improvingButPresent), any())).thenReturn(Optional.of(event("completato")));
    }

    @Test
    void targetQueryReturnsOnlyImprovingLumbarNoShows() {
        QueryPlan plan = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, true, "test");

        List<PatientResult> results = service.run(plan, REFERENCE, null).results();

        assertThat(results)
                .extracting(PatientResult::cognome)
                .containsExactly("Rossi");
    }

    @Test
    void worseningFilterReturnsTheNonImprovingPatient() {
        QueryPlan plan = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), false, true, 3, false, "test");

        List<PatientResult> results = service.run(plan, REFERENCE, null).results();

        assertThat(results)
                .extracting(PatientResult::cognome)
                .containsExactly("Ferrari");
    }

    @Test
    void withoutAreaFilterAllPatientsWithRecentNotesAreConsidered() {
        QueryPlan plan = new QueryPlan(EnumSet.noneOf(BodyArea.class), false, false, 3, false, "test");

        List<PatientResult> results = service.run(plan, REFERENCE, null).results();

        assertThat(results).hasSize(4);
    }

    @Test
    void improvementEvidenceOnAnotherBodyAreaDoesNotCount() {
        // Giuseppe improves, but only on the cervical spine: a lumbar query
        // must not include him even without the no-show filter.
        QueryPlan plan = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, false, "test");

        List<PatientResult> results = service.run(plan, REFERENCE, null).results();

        assertThat(results)
                .extracting(PatientResult::cognome)
                .containsExactlyInAnyOrder("Rossi", "Neri");
    }

    @Test
    void traceExplainsEveryExclusion() {
        QueryPlan plan = new QueryPlan(EnumSet.of(BodyArea.LUMBAR), true, false, 3, true, "test");

        SmartQueryService.Execution execution = service.run(plan, REFERENCE, null);

        assertThat(execution.results()).extracting(PatientResult::cognome).containsExactly("Rossi");
        assertThat(execution.trace().notesInWindow()).isEqualTo(6);
        assertThat(execution.trace().notesMatchingArea()).isEqualTo(5);
        assertThat(execution.trace().patientsInWindow()).isEqualTo(4);
        assertThat(execution.trace().excluded())
                .extracting(SmartQueryService.Exclusion::cognome, SmartQueryService.Exclusion::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Ferrari", "nessun miglioramento nell'area richiesta (punteggio -4 su 2 note)"),
                        org.assertj.core.groups.Tuple.tuple("Verdi", "nessuna nota sull'area richiesta nella finestra"),
                        org.assertj.core.groups.Tuple.tuple("Neri", "ultimo appuntamento completato il 20/12/2024, non saltato"));
    }

    @Test
    void professionalScopeOnlyReadsThatProfessionalsNotes() {
        ObjectId professionista = new ObjectId();
        when(insightRepository.findByProfessionistaIdAndDataGreaterThanEqualAndDataLessThanEqual(eq(professionista), any(), any()))
                .thenReturn(List.of(insight(improvingNoShow, "2024-11-15", Set.of("LUMBAR"), 2)));
        QueryPlan plan = new QueryPlan(EnumSet.noneOf(BodyArea.class), false, false, 3, false, "test");

        List<PatientResult> results = service.run(plan, REFERENCE, professionista).results();

        assertThat(results).extracting(PatientResult::cognome).containsExactly("Rossi");
        verify(insightRepository, never()).findByDataGreaterThanEqualAndDataLessThanEqual(any(), any());
    }

    private NoteInsight insight(ObjectId patient, String date, Set<String> areas, int score) {
        NoteInsight insight = new NoteInsight();
        insight.setId("schede_valutazione:" + new ObjectId());
        insight.setPazienteId(patient);
        insight.setData(Instant.parse(date + "T10:00:00Z"));
        insight.setBodyAreas(areas);
        insight.setImprovementScore(score);
        insight.setExcerpt("nota clinica di test");
        insight.setEvidence(List.of());
        insight.setAnalyzer("heuristic");
        return insight;
    }

    private Paziente patient(ObjectId id, String nome, String cognome) {
        Paziente paziente = new Paziente();
        paziente.setId(id.toHexString());
        paziente.setNome(nome);
        paziente.setCognome(cognome);
        paziente.setEta(50);
        paziente.setStato("attivo");
        return paziente;
    }

    private EventoCalendario event(String stato) {
        EventoCalendario evento = new EventoCalendario();
        evento.setId(new ObjectId().toHexString());
        evento.setData(Instant.parse("2024-12-20T09:00:00Z"));
        evento.setStato(stato);
        return evento;
    }
}
