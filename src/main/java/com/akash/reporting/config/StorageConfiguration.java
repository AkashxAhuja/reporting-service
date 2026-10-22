package com.akash.reporting.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Client reportS3Client(StorageProperties properties) {
        var builder = S3Client.builder().region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // Parts already have a known length; avoid signed chunk framing on S3-compatible endpoints.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle())
                        .chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)));
        if (properties.endpoint() != null) builder.endpointOverride(properties.endpoint());
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner reportS3Presigner(StorageProperties properties) {
        var builder = S3Presigner.builder().region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build());
        if (properties.endpoint() != null) builder.endpointOverride(properties.endpoint());
        return builder.build();
    }
}
