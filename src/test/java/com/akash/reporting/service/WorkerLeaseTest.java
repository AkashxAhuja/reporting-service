package com.akash.reporting.service;

import java.time.*;
import java.util.UUID;
import com.akash.reporting.config.WorkerProperties;
import com.akash.reporting.model.WorkerClaim;
import com.akash.reporting.repository.ReportWorkerRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerLeaseTest {
    @Test void failedRenewalStopsAnAttempt() throws Exception {
        var repository = mock(ReportWorkerRepository.class);
        var claim = new WorkerClaim(UUID.randomUUID().toString(), "token", Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        var properties = new WorkerProperties(true, 2, Duration.ofSeconds(3), Duration.ofSeconds(1),
                Duration.ofMinutes(1), 30, "jdbc:mysql://localhost/test", "reader", "password");
        try (var lease = new WorkerLease(repository, claim, properties, Clock.systemUTC())) {
            verify(repository, timeout(3000)).renew(eq(claim), any(), eq(Duration.ofSeconds(3)));
            long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            boolean stopped = false;
            while (System.nanoTime() < end) {
                try { lease.check(); }
                catch (WorkerLease.OwnershipLostException expected) { stopped = true; break; }
                Thread.sleep(10);
            }
            assertThat(stopped).isTrue();
        }
    }
}
