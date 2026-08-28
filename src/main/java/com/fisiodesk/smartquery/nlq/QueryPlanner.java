package com.fisiodesk.smartquery.nlq;

/** Translates a natural-language question into a {@link QueryPlan}. */
public interface QueryPlanner {

    QueryPlan plan(String question);
}
