package com.akash.reporting.model;

import java.time.Instant;

public record DispatchMetadata(
        String token,
        Instant leaseUntil,
        Instant nextAttemptAt,
        int attemptCount,
        Instant publishedAt,
        String lastError) {

    public static DispatchMetadata pending() {
        return new DispatchMetadata(null, null, null, 0, null, null);
    }
}
