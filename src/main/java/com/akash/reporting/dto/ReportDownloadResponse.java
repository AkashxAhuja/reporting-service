package com.akash.reporting.dto;

import java.net.URI;
import java.time.Instant;

public record ReportDownloadResponse(URI downloadUrl, Instant expiresAt, String fileName) { }
