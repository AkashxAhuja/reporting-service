package com.akash.reporting.config;

import java.net.URI;
import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("reporting.storage")
public record StorageProperties(URI endpoint, @NotBlank String region, @NotBlank String bucket,
        @NotBlank String accessKey, @NotBlank String secretKey, boolean pathStyle,
        @Min(5242880) @Max(67108864) int partSizeBytes,
        @Min(1) long maxOutputBytes, @NotNull Duration downloadTtl) {
    @AssertTrue(message = "Download TTL must be between 1s and 1h, and output limit must fit within 10000 parts")
    public boolean isLimitsValid() {
        return (downloadTtl == null || (downloadTtl.compareTo(Duration.ofSeconds(1)) >= 0
                && downloadTtl.compareTo(Duration.ofHours(1)) <= 0))
                && maxOutputBytes <= 10000L * partSizeBytes;
    }
}
