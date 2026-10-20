package com.akash.reporting.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.TypeAlias;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("report_jobs")
@TypeAlias("report-job")
public record ReportJob(
        @Id String id,
        Instant startInclusive,
        Instant endExclusive,
        String format,
        ReportStatus status,
        Instant submittedAt,
        Instant firstStartedAt,
        Instant completedAt,
        int attemptCount,
        boolean dispatchPending,
        DispatchMetadata dispatch) {
}
