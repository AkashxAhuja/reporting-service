package com.akash.reporting.model;

import java.time.Instant;

public record WorkerMetadata(String token, Instant leaseUntil) { }
