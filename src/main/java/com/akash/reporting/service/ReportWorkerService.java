package com.akash.reporting.service;

import java.time.Clock;
import com.akash.reporting.config.WorkerProperties;
import com.akash.reporting.export.ReportCsvWriter;
import com.akash.reporting.model.ReportArtifact;
import com.akash.reporting.model.ReportEvent;
import com.akash.reporting.model.ReportFailure;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.model.WorkerClaim;
import com.akash.reporting.repository.ReportEventRepository;
import com.akash.reporting.repository.ReportJobRepository;
import com.akash.reporting.repository.ReportWorkerRepository;
import com.akash.reporting.storage.ReportStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "reporting.worker", name = "enabled", havingValue = "true")
public class ReportWorkerService {
    private static final Logger log = LoggerFactory.getLogger(ReportWorkerService.class);
    private final ReportWorkerRepository workers;
    private final ReportJobRepository jobs;
    private final ReportEventRepository events;
    private final ReportStorage storage;
    private final WorkerProperties properties;
    private final Clock clock;

    public ReportWorkerService(ReportWorkerRepository workers, ReportJobRepository jobs, ReportEventRepository events,
            ReportStorage storage, WorkerProperties properties, Clock clock) {
        this.workers = workers;
        this.jobs = jobs;
        this.events = events;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    // true means a durable terminal outcome (or an unknown ID); false asks for redelivery.
    public boolean process(String reportId) {
        var claimed = workers.claim(reportId, clock.instant(), properties.leaseDuration());
        if (claimed.isEmpty()) {
            return jobs.findById(reportId).map(job -> job.status() == ReportStatus.COMPLETED
                    || job.status() == ReportStatus.FAILED).orElse(true);
        }
        var claim = claimed.orElseThrow();
        String key = "reports/" + reportId + "/" + claim.token() + ".csv";
        try (var lease = new WorkerLease(workers, claim, properties, clock)) {
            ReportArtifact artifact;
            try {
                artifact = export(claim, lease, key);
                lease.check();
            } catch (WorkerLease.OwnershipLostException lost) {
                deleteAttempt(key);
                return false;
            } catch (Exception failure) {
                log.error("Report {} failed", reportId, failure);
                deleteAttempt(key);
                return workers.fail(claim, clock.instant(), new ReportFailure("EXPORT_FAILED",
                        "Report generation failed. Check the service logs and submit a new report."));
            }
            // A Mongo timeout can mean the update succeeded. Preserve the object and redeliver the message.
            boolean saved = workers.complete(claim, clock.instant(), artifact);
            if (!saved) {
                deleteAttempt(key);
                return false;
            }
            log.info("Completed report {}: {} rows, {} bytes", reportId, artifact.rowCount(), artifact.byteSize());
            return true;
        }
    }

    private ReportArtifact export(WorkerClaim claim, WorkerLease lease, String key) throws java.io.IOException {
        try (var upload = storage.begin(key)) {
            var csv = new ReportCsvWriter(upload);
            ReportEvent after = null;
            long rows = 0;
            while (true) {
                lease.check();
                var batch = events.nextBatch(claim.startInclusive(), claim.endExclusive(), after);
                if (batch.isEmpty()) break;
                for (var event : batch) {
                    lease.check();
                    csv.write(event);
                    rows++;
                }
                after = batch.getLast();
            }
            lease.check();
            csv.flush();
            lease.check();
            long bytes = upload.complete();
            return new ReportArtifact(key, "report-" + claim.reportId() + ".csv", rows, bytes);
        }
    }

    private void deleteAttempt(String key) {
        try { storage.delete(key); }
        catch (RuntimeException cleanupFailure) { log.warn("Could not remove attempt object {}", key, cleanupFailure); }
    }
}
