package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.NoteInsight;
import java.time.Instant;
import org.bson.types.ObjectId;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NoteInsightRepository extends MongoRepository<NoteInsight, String> {

    // Between is exclusive on both ends in Spring Data MongoDB: spell the bounds out.
    List<NoteInsight> findByDataGreaterThanEqualAndDataLessThanEqual(Instant from, Instant to);

    List<NoteInsight> findByProfessionistaIdAndDataGreaterThanEqualAndDataLessThanEqual(
            ObjectId professionistaId, Instant from, Instant to);

    long countByAnalyzer(String analyzer);
}
