package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.NoteInsight;
import java.time.Instant;
import org.bson.types.ObjectId;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface NoteInsightRepository extends MongoRepository<NoteInsight, String> {

    // Derived "Between" is exclusive and a derived query cannot bound the same
    // field twice: explicit inclusive range.
    @Query("{ 'data': { '$gte': ?0, '$lte': ?1 } }")
    List<NoteInsight> findByDataGreaterThanEqualAndDataLessThanEqual(Instant from, Instant to);

    @Query("{ 'professionista_id': ?0, 'data': { '$gte': ?1, '$lte': ?2 } }")
    List<NoteInsight> findByProfessionistaIdAndDataGreaterThanEqualAndDataLessThanEqual(
            ObjectId professionistaId, Instant from, Instant to);

    long countByAnalyzer(String analyzer);
}
