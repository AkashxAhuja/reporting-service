package com.akash.reporting.service;

import java.time.Clock;

import com.akash.reporting.config.DispatchProperties;
import com.akash.reporting.exception.ReportPublishException;
import com.akash.reporting.messaging.ReportQueuePublisher;
import com.akash.reporting.repository.ReportDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "reporting.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReportDispatcher.class);
    private final ReportDispatchRepository repository;
    private final ReportQueuePublisher publisher;
    private final DispatchProperties properties;
    private final Clock clock;

    public ReportDispatcher(ReportDispatchRepository repository, ReportQueuePublisher publisher,
                            DispatchProperties properties, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    public void dispatchPendingJobs() {
        try {
            for (int dispatched = 0; dispatched < properties.batchSize(); dispatched++) {
                var claimed = repository.claimNext(clock.instant(), properties.leaseDuration());
                if (claimed.isEmpty()) {
                    return;
                }
                var claim = claimed.get();
                try {
                    publisher.publish(claim.reportId());
                } catch (ReportPublishException exception) {
                    if (repository.reschedule(claim, clock.instant().plus(properties.retryDelay()), exception.getMessage())) {
                        log.warn("Report {} remains pending after a dispatch failure", claim.reportId(), exception);
                    } else {
                        log.warn("Report {} dispatch claim changed before its failure was recorded", claim.reportId(), exception);
                    }
                    return; // Avoid repeatedly hitting an unavailable broker in the same polling cycle.
                }
                if (repository.markPublished(claim, clock.instant())) {
                    log.info("Report {} publication confirmed by RabbitMQ", claim.reportId());
                } else {
                    log.warn("Report {} dispatch claim changed before publication was recorded", claim.reportId());
                }
            }
        } catch (DataAccessException exception) {
            // An unconfirmed metadata update leaves the job recoverable after its lease expires.
            log.warn("Dispatch metadata operation failed; pending jobs will be retried", exception);
        }
    }
}
