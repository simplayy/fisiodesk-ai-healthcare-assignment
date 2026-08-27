package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.DiarioTrattamento;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DiarioTrattamentoRepository extends MongoRepository<DiarioTrattamento, String> {
}
