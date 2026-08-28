package com.fisiodesk.smartquery.web;

import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Professionals present in the data, for the scope selector of the demo UI.
 * There is no professionals collection in the dataset: the list is derived
 * from {@code pazienti.professionista_principale}. In the real product the
 * scope comes from the authenticated user, not from a list.
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
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria
                        .where("professionista_principale").ne(null)),
                Aggregation.group("professionista_principale").count().as("pazienti"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id"));
        return mongoTemplate.aggregate(aggregation, "pazienti", Document.class)
                .getMappedResults().stream()
                .map(d -> new ProfessionistaView(d.get("_id").toString(), ((Number) d.get("pazienti")).longValue()))
                .toList();
    }
}
