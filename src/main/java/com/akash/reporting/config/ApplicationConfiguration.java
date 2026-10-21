package com.akash.reporting.config;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

import com.mongodb.WriteConcern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReportProperties.class, DispatchProperties.class, WorkerProperties.class, StorageProperties.class})
public class ApplicationConfiguration {

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
