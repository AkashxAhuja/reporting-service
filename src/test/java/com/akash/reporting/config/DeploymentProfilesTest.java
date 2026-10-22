package com.akash.reporting.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeploymentProfilesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ApplicationConfiguration.class, WorkerConfiguration.class)
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));

    @Test
    void plainStartupDefaultsToApiWithoutAMysqlPool() {
        context.withPropertyValues("spring.profiles.active=").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getEnvironment().getDefaultProfiles()).containsExactly("api");
            assertThat(application.getBean(WorkerProperties.class).enabled()).isFalse();
            assertThat(application.getBean(DispatchProperties.class).enabled()).isTrue();
            assertThat(application).doesNotHaveBean(HikariDataSource.class).doesNotHaveBean(JdbcTemplate.class);
        });
    }

    @Test
    void apiProfileDoesNotCreateWorkerResourcesEvenWithTheLegacyEnableVariable() {
        context.withPropertyValues("spring.profiles.active=api", "REPORT_WORKER_ENABLED=true")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(WorkerProperties.class).enabled()).isFalse();
                    assertThat(application).doesNotHaveBean(HikariDataSource.class)
                            .doesNotHaveBean("reportWorkerContainerFactory");
                });
    }

    @Test
    void workerProfileEnablesOneReaderAndDisablesHttpAndDispatch() {
        context.withPropertyValues("spring.profiles.active=worker").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(WorkerProperties.class).enabled()).isTrue();
            assertThat(application.getBean(DispatchProperties.class).enabled()).isFalse();
            assertThat(application.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
            assertThat(application).hasSingleBean(JdbcTemplate.class).hasBean("reportWorkerContainerFactory");
            assertThat(application.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
        });
    }

    @Test
    void localProfileEnablesBothDispatchAndGeneration() {
        context.withPropertyValues("spring.profiles.active=local").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(WorkerProperties.class).enabled()).isTrue();
            assertThat(application.getBean(DispatchProperties.class).enabled()).isTrue();
            assertThat(application.getEnvironment().getProperty("spring.main.web-application-type")).isNotEqualTo("none");
            assertThat(application).hasSingleBean(HikariDataSource.class);
        });
    }
}
