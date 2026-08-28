package com.fisiodesk.smartquery.web;

import com.fisiodesk.smartquery.nlq.QueryPlanningService;
import com.fisiodesk.smartquery.query.QueryFacade;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Empties the answer cache and the stored plans; note insights are left alone. */
@RestController
@RequestMapping("/api")
public class CacheController {

    private final QueryFacade queryFacade;
    private final QueryPlanningService planningService;

    public CacheController(QueryFacade queryFacade, QueryPlanningService planningService) {
        this.queryFacade = queryFacade;
        this.planningService = planningService;
    }

    @DeleteMapping("/cache")
    public Map<String, Long> clear() {
        return Map.of(
                "responses", queryFacade.clearResponseCache(),
                "plans", planningService.clearStoredPlans());
    }
}
