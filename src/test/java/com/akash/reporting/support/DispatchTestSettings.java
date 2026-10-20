package com.akash.reporting.support;

import java.time.Duration;

import com.akash.reporting.config.DispatchProperties;

public final class DispatchTestSettings {

    private DispatchTestSettings() {
    }

    public static DispatchProperties defaults() {
        return new DispatchProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofMillis(50), Duration.ofSeconds(5), 10,
                "reporting.test.exchange", "reporting.test.jobs", "report.requested");
    }
}
