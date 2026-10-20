package com.akash.reporting.report;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record CreateReportRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate) {
}
