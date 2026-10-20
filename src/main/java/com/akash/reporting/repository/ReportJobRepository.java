package com.akash.reporting.repository;

import com.akash.reporting.model.ReportJob;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReportJobRepository extends MongoRepository<ReportJob, String> {
}
