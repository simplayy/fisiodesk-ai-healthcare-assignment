package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Pre-computed analysis of a single clinical note. This is the core of the
 * "index, don't re-analyze" strategy: every free-text note is analyzed once
 * (heuristic or LLM), the outcome is persisted here and queries only touch
 * this collection plus the calendar. The text hash lets the indexer detect
 * edited notes and re-analyze only those.
 */
@Document("note_insights")
// Query execution reads the window by date; the compound index serves per-patient timelines.
@CompoundIndex(name = "paziente_data_idx", def = "{'paziente_id': 1, 'data': -1}")
@CompoundIndex(name = "professionista_data_idx", def = "{'professionista_id': 1, 'data': -1}")
public class NoteInsight {

    /** id = "<sourceCollection>:<noteId>" so re-indexing is an idempotent upsert. */
    @Id
    private String id;

    @Field("note_id")
    private String noteId;

    @Field("source_collection")
    private String sourceCollection;

    @Field("paziente_id")
    private ObjectId pazienteId;

    @Field("professionista_id")
    private ObjectId professionistaId;

    @Indexed(name = "data_idx")
    private Instant data;

    @Field("text_hash")
    private String textHash;

    @Field("body_areas")
    private Set<String> bodyAreas;

    /** Negative = worsening/stationary, 0 = no signal, positive = improving. */
    @Field("improvement_score")
    private int improvementScore;

    /** Short excerpt of the note, shown to the professional as supporting evidence. */
    private String excerpt;

    /** Terms/phrases that triggered the classification (for explainability). */
    private List<String> evidence;

    private String analyzer;

    @Field("analyzed_at")
    private Instant analyzedAt;

    public String getId() {
        return id;
    }

    public String getNoteId() {
        return noteId;
    }

    public String getSourceCollection() {
        return sourceCollection;
    }

    public ObjectId getPazienteId() {
        return pazienteId;
    }

    public ObjectId getProfessionistaId() {
        return professionistaId;
    }

    public Instant getData() {
        return data;
    }

    public String getTextHash() {
        return textHash;
    }

    public Set<String> getBodyAreas() {
        return bodyAreas;
    }

    public int getImprovementScore() {
        return improvementScore;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public Instant getAnalyzedAt() {
        return analyzedAt;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setNoteId(String noteId) {
        this.noteId = noteId;
    }

    public void setSourceCollection(String sourceCollection) {
        this.sourceCollection = sourceCollection;
    }

    public void setPazienteId(ObjectId pazienteId) {
        this.pazienteId = pazienteId;
    }

    public void setProfessionistaId(ObjectId professionistaId) {
        this.professionistaId = professionistaId;
    }

    public void setData(Instant data) {
        this.data = data;
    }

    public void setTextHash(String textHash) {
        this.textHash = textHash;
    }

    public void setBodyAreas(Set<String> bodyAreas) {
        this.bodyAreas = bodyAreas;
    }

    public void setImprovementScore(int improvementScore) {
        this.improvementScore = improvementScore;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public void setAnalyzedAt(Instant analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
