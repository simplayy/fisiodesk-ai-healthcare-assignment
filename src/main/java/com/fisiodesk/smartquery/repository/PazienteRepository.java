package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.Paziente;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PazienteRepository extends MongoRepository<Paziente, String> {
}
