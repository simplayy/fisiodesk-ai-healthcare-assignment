package com.fisiodesk.smartquery.analysis;

import com.fisiodesk.smartquery.domain.ClinicalNote;
import com.fisiodesk.smartquery.domain.NoteInsight;
import com.fisiodesk.smartquery.repository.DiarioTrattamentoRepository;
import com.fisiodesk.smartquery.repository.NoteInsightRepository;
import com.fisiodesk.smartquery.config.AiProperties;
import com.fisiodesk.smartquery.repository.SchedaValutazioneRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Incremental batch indexer. Every run it compares the source notes against
 * the note_insights collection and (re)analyzes only new or edited notes.
 * Runs at startup and then periodically, so notes added while the system is
 * live become searchable within one cycle without any manual step.
 *
 * This is where AI cost is paid: once per note version, never per query.
 */
@Component
public class InsightIndexer {

    private static final Logger log = LoggerFactory.getLogger(InsightIndexer.class);

    public static final String SOURCE_SCHEDE = "schede_valutazione";
    public static final String SOURCE_DIARIO = "diario_trattamenti";

    private final SchedaValutazioneRepository schedaRepository;
    private final DiarioTrattamentoRepository diarioRepository;
    private final NoteInsightRepository insightRepository;
    private final TextAnalysisService analysisService;
    private final AiProperties aiProperties;

    public InsightIndexer(SchedaValutazioneRepository schedaRepository,
                          DiarioTrattamentoRepository diarioRepository,
                          NoteInsightRepository insightRepository,
                          TextAnalysisService analysisService,
                          AiProperties aiProperties) {
        this.schedaRepository = schedaRepository;
        this.diarioRepository = diarioRepository;
        this.insightRepository = insightRepository;
        this.analysisService = analysisService;
        this.aiProperties = aiProperties;
    }

    @Scheduled(initialDelayString = "${smartquery.indexer.initial-delay-ms:3000}",
               fixedDelayString = "${smartquery.indexer.fixed-delay-ms:30000}")
    public void indexPendingNotes() {
        try {
            Map<String, NoteInsight> existing = insightRepository.findAll().stream()
                    .collect(Collectors.toMap(NoteInsight::getId, Function.identity()));

            int updated = indexCollection(SOURCE_SCHEDE, schedaRepository.findAll(), existing)
                    + indexCollection(SOURCE_DIARIO, diarioRepository.findAll(), existing);
            if (updated > 0) {
                log.info("Indexed {} clinical notes ({} already up to date)", updated, existing.size());
            }
        } catch (Exception e) {
            // Typically a race with the seed container at first startup; the
            // next scheduled run will catch up.
            log.warn("Indexing cycle skipped: {}", e.getMessage());
        }
    }

    /** Analyzes and saves each pending note individually, so progress survives a restart mid-batch. */
    private int indexCollection(String source,
                                List<? extends ClinicalNote> notes,
                                Map<String, NoteInsight> existing) {
        int updated = 0;
        for (ClinicalNote note : notes) {
            if (note.getDescrizione() == null || note.getDescrizione().isBlank()) {
                continue;
            }
            String id = source + ":" + note.getId();
            String hash = analysisService.hash(note.getDescrizione());
            NoteInsight current = existing.get(id);
            if (current != null && hash.equals(current.getTextHash()) && !shouldUpgrade(current)) {
                continue;
            }
            ClinicalNoteAnalysis analysis = analysisService.analyze(note.getDescrizione());
            insightRepository.save(toInsight(id, source, note, hash, analysis));
            updated++;
        }
        return updated;
    }

    /**
     * When the LLM becomes available, notes previously classified by the
     * heuristic fallback are re-analyzed once. The reverse never happens:
     * disabling the LLM keeps the richer analysis already paid for.
     */
    private boolean shouldUpgrade(NoteInsight current) {
        return aiProperties.enabled() && !"llm".equals(current.getAnalyzer());
    }

    private NoteInsight toInsight(String id, String source, ClinicalNote note,
                                  String hash, ClinicalNoteAnalysis analysis) {
        NoteInsight insight = new NoteInsight();
        insight.setId(id);
        insight.setNoteId(note.getId());
        insight.setSourceCollection(source);
        insight.setPazienteId(note.getPazienteId());
        insight.setProfessionistaId(note.getProfessionistaId());
        insight.setData(note.getData());
        insight.setTextHash(hash);
        insight.setBodyAreas(analysis.bodyAreas().stream()
                .map(Enum::name).collect(Collectors.toSet()));
        insight.setImprovementScore(analysis.improvementScore());
        insight.setExcerpt(excerpt(note.getDescrizione()));
        insight.setEvidence(analysis.evidence());
        insight.setAnalyzer(analysis.analyzer());
        insight.setAnalyzedAt(Instant.now());
        return insight;
    }

    private String excerpt(String text) {
        return text.length() <= 220 ? text : text.substring(0, 217) + "...";
    }
}
