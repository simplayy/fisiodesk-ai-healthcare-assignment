package com.fisiodesk.smartquery.web;

import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Professionals found in the data, for the selector of the demo UI (derived
 * from pazienti.professionista_principale, there is no professionals collection).
 */
@RestController
@RequestMapping("/api")
public class ProfessionistaController {

    public record ProfessionistaView(String id, long pazienti) {
    }

    private final MongoTemplate mongoTemplate;

    public ProfessionistaController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/professionisti")
    public List<ProfessionistaView> list() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("professionista_principale").ne(null)),
                Aggregation.group("professionista_principale").count().as("pazienti"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        return mongoTemplate.aggregate(aggregation, "pazienti", Document.class)
                .getMappedResults().stream()
                .map(d -> new ProfessionistaView(d.get("_id").toString(), ((Number) d.get("pazienti")).longValue()))
                .toList();
    }
}
