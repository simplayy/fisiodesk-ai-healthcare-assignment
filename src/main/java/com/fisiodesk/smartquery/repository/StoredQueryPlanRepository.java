package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.StoredQueryPlan;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StoredQueryPlanRepository extends MongoRepository<StoredQueryPlan, String> {
}
