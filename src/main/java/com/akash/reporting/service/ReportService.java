package com.akash.reporting.service;

import com.akash.reporting.config.ReportProperties;
import com.akash.reporting.dto.CreateReportRequest;
import com.akash.reporting.exception.InvalidReportRequestException;
import com.akash.reporting.exception.ReportNotFoundException;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.DispatchMetadata;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.repository.ReportJobRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final ReportJobRepository repository;
    private final ReportProperties properties;
    private final Clock clock;

    public ReportService(ReportJobRepository repository, ReportProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public ReportJob create(CreateReportRequest request) {
        long rangeDays = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
        if (rangeDays <= 0) {
            throw new InvalidReportRequestException("endDate must be after startDate.");
        }
        if (rangeDays > properties.maxRangeDays()) {
            throw new InvalidReportRequestException(
                    "Date range must not exceed " + properties.maxRangeDays() + " day(s).");
        }

        // MongoDB dates have millisecond precision, so POST and GET return the same timestamp.
        var submittedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var job = new ReportJob(
                UUID.randomUUID().toString(),
                request.startDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                request.endDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                "CSV",
                ReportStatus.QUEUED,
                submittedAt,
                null,
                null,
                0,
                true,
                DispatchMetadata.pending(), null, null, null);

        // A single insert stores both the job and the intention to dispatch it.
        return repository.insert(job);
    }

    public ReportJob get(UUID reportId) {
        return repository.findById(reportId.toString()).orElseThrow(ReportNotFoundException::new);
    }
}
