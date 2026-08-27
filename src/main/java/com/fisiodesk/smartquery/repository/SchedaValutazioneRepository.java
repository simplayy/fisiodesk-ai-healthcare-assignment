package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.SchedaValutazione;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SchedaValutazioneRepository extends MongoRepository<SchedaValutazione, String> {
}
