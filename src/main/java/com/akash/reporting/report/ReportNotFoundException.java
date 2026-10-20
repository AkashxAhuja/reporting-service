package com.akash.reporting.report;

public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException() {
        super("Report not found.");
    }
}
