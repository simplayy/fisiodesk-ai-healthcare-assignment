package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.NoteInsight;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NoteInsightRepository extends MongoRepository<NoteInsight, String> {

    List<NoteInsight> findByDataBetween(Instant from, Instant to);

    long countByAnalyzer(String analyzer);
}
