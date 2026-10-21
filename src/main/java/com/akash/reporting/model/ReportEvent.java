package com.akash.reporting.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// The source DATETIME(6) column represents UTC; no system-default timezone conversion.
public record ReportEvent(long id, LocalDateTime occurredAt, String reference, BigDecimal amount, String status) { }
