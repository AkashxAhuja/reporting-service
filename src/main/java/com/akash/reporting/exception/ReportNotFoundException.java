package com.akash.reporting.exception;

public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException() {
        super("Report not found.");
    }
}
