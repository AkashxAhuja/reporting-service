package com.akash.reporting;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

import com.akash.reporting.report.ReportProperties;
import com.mongodb.WriteConcern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(ReportProperties.class)
public class ReportingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MongoClientSettingsBuilderCustomizer durableMongoWrites() {
        return builder -> builder.writeConcern(
                WriteConcern.MAJORITY.withJournal(true).withWTimeout(5, TimeUnit.SECONDS));
    }
}
