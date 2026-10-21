package com.akash.reporting.config;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("reporting.worker")
public record WorkerProperties(boolean enabled, @Min(1) @Max(5000) int batchSize,
        @NotNull Duration leaseDuration, @NotNull Duration heartbeatInterval,
        @NotNull Duration maxDuration, @Min(1) @Max(120) int queryTimeoutSeconds,
        @NotBlank String jdbcUrl, @NotBlank String username, @NotBlank String password) {
    @AssertTrue(message = "Worker heartbeat must be >= 1s, lease >= 3 heartbeats, and max-duration between lease and 15m")
    public boolean isTimingValid() {
        if (leaseDuration == null || heartbeatInterval == null || maxDuration == null) return true;
        return heartbeatInterval.compareTo(Duration.ofSeconds(1)) >= 0
                && leaseDuration.compareTo(heartbeatInterval.multipliedBy(3)) >= 0
                && maxDuration.compareTo(leaseDuration) >= 0
                && maxDuration.compareTo(Duration.ofMinutes(15)) <= 0;
    }
}
