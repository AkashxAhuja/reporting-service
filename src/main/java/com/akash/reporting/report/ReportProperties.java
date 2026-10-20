package com.akash.reporting.report;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("reporting.reports")
public record ReportProperties(@Min(1) int maxRangeDays) {
}
