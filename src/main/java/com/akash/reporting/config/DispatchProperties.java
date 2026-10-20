package com.akash.reporting.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("reporting.dispatch")
public record DispatchProperties(
        boolean enabled,
        @NotNull Duration pollInterval,
        @NotNull Duration leaseDuration,
        @NotNull Duration confirmTimeout,
        @NotNull Duration retryDelay,
        @Min(1) @Max(100) int batchSize,
        @NotBlank String exchange,
        @NotBlank String queue,
        @NotBlank String routingKey) {

    @AssertTrue(message = "Dispatch durations must be at least 1ms and lease-duration must exceed confirm-timeout")
    public boolean isTimingValid() {
        if (pollInterval == null || leaseDuration == null || confirmTimeout == null || retryDelay == null) {
            return true; // @NotNull reports missing settings.
        }
        return pollInterval.toMillis() > 0 && retryDelay.toMillis() > 0
                && confirmTimeout.toMillis() > 0 && leaseDuration.compareTo(confirmTimeout) > 0;
    }
}
