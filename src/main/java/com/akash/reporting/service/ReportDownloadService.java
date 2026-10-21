package com.akash.reporting.service;

import java.time.Clock;
import java.util.UUID;
import com.akash.reporting.config.StorageProperties;
import com.akash.reporting.dto.ReportDownloadResponse;
import com.akash.reporting.exception.ReportNotReadyException;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.storage.ReportStorage;
import org.springframework.stereotype.Service;

@Service
public class ReportDownloadService {
    private final ReportService reports;
    private final ReportStorage storage;
    private final StorageProperties properties;
    private final Clock clock;

    public ReportDownloadService(ReportService reports, ReportStorage storage, StorageProperties properties, Clock clock) {
        this.reports = reports; this.storage = storage; this.properties = properties; this.clock = clock;
    }

    public ReportDownloadResponse download(UUID id) {
        var job = reports.get(id);
        if (job.status() != ReportStatus.COMPLETED || job.artifact() == null) throw new ReportNotReadyException();
        return new ReportDownloadResponse(storage.download(job.artifact().objectKey(), job.artifact().fileName()),
                clock.instant().plus(properties.downloadTtl()), job.artifact().fileName());
    }
}
