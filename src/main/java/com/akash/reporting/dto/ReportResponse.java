package com.akash.reporting.dto;

import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.ReportStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public record ReportResponse(
        String reportId,
        LocalDate startDate,
        LocalDate endDate,
        String format,
        ReportStatus status,
        Instant submittedAt,
        Instant firstStartedAt,
        Instant completedAt,
        int attemptCount,
        int retryCount,
        String statusUrl,
        Long rowCount,
        Long byteSize,
        String downloadUrl,
        com.akash.reporting.model.ReportFailure failure) {

    public static ReportResponse from(ReportJob job) {
        return new ReportResponse(
                job.id(),
                job.startInclusive().atOffset(ZoneOffset.UTC).toLocalDate(),
                job.endExclusive().atOffset(ZoneOffset.UTC).toLocalDate(),
                job.format(),
                job.status(),
                job.submittedAt(),
                job.firstStartedAt(),
                job.completedAt(),
                job.attemptCount(),
                Math.max(0, job.attemptCount() - 1),
                "/api/v1/reports/" + job.id(),
                job.artifact() == null ? null : job.artifact().rowCount(),
                job.artifact() == null ? null : job.artifact().byteSize(),
                job.status() == ReportStatus.COMPLETED ? "/api/v1/reports/" + job.id() + "/download" : null,
                job.failure());
    }
}
