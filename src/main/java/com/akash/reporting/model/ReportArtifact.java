package com.akash.reporting.model;

public record ReportArtifact(String objectKey, String fileName, long rowCount, long byteSize) { }
