package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** LLM plan of a question, stored so it is computed once across restarts and instances. */
@Document("query_plans")
public class StoredQueryPlan {

    /** SHA-256 of the normalized question. */
    @Id
    private String id;

    private String question;

    @Field("body_areas")
    private Set<String> bodyAreas;

    @Field("require_improvement")
    private boolean requireImprovement;

    @Field("require_worsening")
    private boolean requireWorsening;

    @Field("window_months")
    private int windowMonths;

    @Field("missed_last_appointment")
    private boolean missedLastAppointment;

    private String planner;

    @Field("created_at")
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public String getQuestion() {
        return question;
    }

    public Set<String> getBodyAreas() {
        return bodyAreas;
    }

    public boolean isRequireImprovement() {
        return requireImprovement;
    }

    public boolean isRequireWorsening() {
        return requireWorsening;
    }

    public int getWindowMonths() {
        return windowMonths;
    }

    public boolean isMissedLastAppointment() {
        return missedLastAppointment;
    }

    public String getPlanner() {
        return planner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public void setBodyAreas(Set<String> bodyAreas) {
        this.bodyAreas = bodyAreas;
    }

    public void setRequireImprovement(boolean requireImprovement) {
        this.requireImprovement = requireImprovement;
    }

    public void setRequireWorsening(boolean requireWorsening) {
        this.requireWorsening = requireWorsening;
    }

    public void setWindowMonths(int windowMonths) {
        this.windowMonths = windowMonths;
    }

    public void setMissedLastAppointment(boolean missedLastAppointment) {
        this.missedLastAppointment = missedLastAppointment;
    }

    public void setPlanner(String planner) {
        this.planner = planner;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
