package com.akash.reporting.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableRabbit
@ConditionalOnProperty(prefix = "reporting.worker", name = "enabled", havingValue = "true")
public class WorkerConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource reportingDataSource(WorkerProperties properties) {
        var config = new HikariConfig();
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(3000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("report-reader");
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        config.addDataSourceProperty("useCursorFetch", "true");
        config.addDataSourceProperty("connectTimeout", "3000");
        config.addDataSourceProperty("socketTimeout", "30000");
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate reportJdbcTemplate(HikariDataSource reportingDataSource, WorkerProperties properties) {
        var jdbc = new JdbcTemplate(reportingDataSource);
        jdbc.setQueryTimeout(properties.queryTimeoutSeconds());
        jdbc.setFetchSize(properties.batchSize());
        return jdbc;
    }

    @Bean
    SimpleRabbitListenerContainerFactory reportWorkerContainerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setRecoveryInterval(5000L);
        return factory;
    }
}
