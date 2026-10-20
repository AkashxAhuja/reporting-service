package com.akash.reporting.report;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReportJobRepository extends MongoRepository<ReportJob, String> {
}
