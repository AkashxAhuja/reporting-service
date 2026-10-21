package com.akash.reporting.model;

import java.time.Instant;

public record WorkerClaim(String reportId, String token, Instant startInclusive, Instant endExclusive) { }
