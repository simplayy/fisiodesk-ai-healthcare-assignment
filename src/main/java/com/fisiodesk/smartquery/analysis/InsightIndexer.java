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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batch indexer. Each run compares the source notes with note_insights and
 * analyzes only new or edited notes (text hash). This is where the AI cost is
 * paid: once per note, never per query.
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
    private static final int MAX_LLM_FAILURES = 3;

    private final long minIntervalNanos;
    private long lastLlmCallNanos;
    private int llmFailures;

    /** @param maxLlmCallsPerMinute pacing of the LLM calls (0 = none), to stay under the provider quota */
    public InsightIndexer(SchedaValutazioneRepository schedaRepository,
                          DiarioTrattamentoRepository diarioRepository,
                          NoteInsightRepository insightRepository,
                          TextAnalysisService analysisService,
                          AiProperties aiProperties,
                          @Value("${smartquery.indexer.max-llm-calls-per-minute:0}") int maxLlmCallsPerMinute) {
        this.schedaRepository = schedaRepository;
        this.diarioRepository = diarioRepository;
        this.insightRepository = insightRepository;
        this.analysisService = analysisService;
        this.aiProperties = aiProperties;
        this.minIntervalNanos = maxLlmCallsPerMinute > 0 ? 60_000_000_000L / maxLlmCallsPerMinute : 0;
    }

    @Scheduled(initialDelayString = "${smartquery.indexer.initial-delay-ms:3000}",
               fixedDelayString = "${smartquery.indexer.fixed-delay-ms:30000}")
    public void indexPendingNotes() {
        try {
            llmFailures = 0;
            Map<String, NoteInsight> existing = insightRepository.findAll().stream()
                    .collect(Collectors.toMap(NoteInsight::getId, Function.identity()));

            int updated = indexCollection(SOURCE_SCHEDE, schedaRepository.findAll(), existing)
                    + indexCollection(SOURCE_DIARIO, diarioRepository.findAll(), existing);
            if (updated > 0) {
                log.info("Indexed {} clinical notes ({} already up to date)", updated, existing.size());
            }
        } catch (Exception e) {
            // Usually the seed container is not done yet; the next run catches up.
            log.warn("Indexing cycle skipped: {}", e.getMessage());
        }
    }

    /** Saves each note as it goes, so progress survives a restart. */
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
            if (!pace()) {
                break;
            }
            ClinicalNoteAnalysis analysis = analysisService.analyze(note.getDescrizione());
            insightRepository.save(toInsight(id, source, note, hash, analysis));
            updated++;
            // Provider failing: stop this cycle instead of retrying every note.
            if (aiProperties.enabled() && !LlmClinicalTextAnalyzer.NAME.equals(analysis.analyzer())
                    && ++llmFailures >= MAX_LLM_FAILURES) {
                log.warn("LLM unavailable after {} notes, retrying at the next cycle", llmFailures);
                break;
            }
        }
        return updated;
    }

    /** Spaces LLM calls to the configured rate; false when interrupted (shutdown). */
    private boolean pace() {
        if (minIntervalNanos == 0 || !aiProperties.enabled()) {
            return true;
        }
        long waitNanos = lastLlmCallNanos + minIntervalNanos - System.nanoTime();
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        lastLlmCallNanos = System.nanoTime();
        return true;
    }

    /** Notes analyzed by the fallback are re-analyzed once when the LLM is available. */
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
