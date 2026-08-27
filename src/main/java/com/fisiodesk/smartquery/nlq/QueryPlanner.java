package com.fisiodesk.smartquery.nlq;

public interface QueryPlanner {

    QueryPlan plan(String question);

    String name();
}
