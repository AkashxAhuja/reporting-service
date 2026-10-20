package com.akash.reporting.exception;

public class ReportPublishException extends RuntimeException {

    public ReportPublishException(String message) {
        super(message);
    }

    public ReportPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
