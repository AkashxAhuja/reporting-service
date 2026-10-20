package com.akash.reporting.scheduler;

import com.akash.reporting.service.ReportDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "reporting.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportDispatchScheduler {

    private final ReportDispatcher dispatcher;

    public ReportDispatchScheduler(ReportDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${reporting.dispatch.poll-interval:1s}",
            initialDelayString = "${reporting.dispatch.poll-interval:1s}")
    public void dispatch() {
        dispatcher.dispatchPendingJobs();
    }
}
