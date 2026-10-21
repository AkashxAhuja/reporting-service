package com.akash.reporting.exception;

public class ReportNotReadyException extends RuntimeException {
    public ReportNotReadyException() { super("A download is available only after the report completes successfully."); }
}
