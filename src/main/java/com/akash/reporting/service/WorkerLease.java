package com.akash.reporting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.akash.reporting.config.WorkerProperties;
import com.akash.reporting.model.WorkerClaim;
import com.akash.reporting.repository.ReportWorkerRepository;

final class WorkerLease implements AutoCloseable {
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledExecutorService heartbeat;
    private final Clock clock;
    private final Instant deadline;

    WorkerLease(ReportWorkerRepository repository, WorkerClaim claim, WorkerProperties properties, Clock clock) {
        this.clock = clock;
        deadline = clock.instant().plus(properties.maxDuration());
        heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("report-lease").factory());
        long interval = properties.heartbeatInterval().toMillis();
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (!repository.renew(claim, clock.instant(), properties.leaseDuration())) lost.set(true);
            } catch (RuntimeException failure) { lost.set(true); }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    void check() {
        if (lost.get() || Thread.currentThread().isInterrupted()) throw new OwnershipLostException();
        if (!clock.instant().isBefore(deadline)) throw new IllegalStateException("Report execution deadline exceeded");
    }

    @Override
    public void close() { heartbeat.shutdownNow(); }

    static final class OwnershipLostException extends RuntimeException { }
}
