package com.akash.reporting.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.akash.reporting.ReportingServiceApplication;
import com.akash.reporting.messaging.ReportJobListener;
import com.akash.reporting.messaging.ReportQueuePublisher;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.repository.ReportEventRepository;
import com.akash.reporting.repository.ReportJobRepository;
import com.akash.reporting.scheduler.ReportDispatchScheduler;
import com.akash.reporting.service.ReportDispatcher;
import com.akash.reporting.service.ReportWorkerService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeploymentRolesIT {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String queue = "reporting.roles.it." + suffix;
    private final String exchange = "reporting.roles.it.exchange." + suffix;
    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();

    @Test
    void twoApiCopiesQueueJobsAndOneHeadlessWorkerGeneratesTheirDownloads() throws Exception {
        var firstApi = start(""); // Exercise the safe default, without selecting a role.
        var secondApi = start("api");
        for (var api : List.of(firstApi, secondApi)) {
            assertThat(api).isInstanceOf(WebServerApplicationContext.class);
            assertThat(api.getBeansOfType(HikariDataSource.class)).isEmpty();
            assertThat(api.getBeansOfType(ReportEventRepository.class)).isEmpty();
            assertThat(api.getBeansOfType(ReportWorkerService.class)).isEmpty();
            assertThat(api.getBeansOfType(ReportJobListener.class)).isEmpty();
            assertThat(api.getBeansOfType(ReportDispatcher.class)).hasSize(1);
        }
        var admin = new RabbitAdmin(firstApi.getBean(RabbitTemplate.class));
        String firstId = submit(firstApi);
        String secondId = submit(secondApi);
        var jobs = firstApi.getBean(ReportJobRepository.class);
        await(() -> {
            var info = admin.getQueueInfo(queue);
            return info != null && info.getMessageCount() == 2;
        });
        assertThat(admin.getQueueInfo(queue).getConsumerCount()).isZero();
        assertThat(jobs.findById(firstId).orElseThrow().status()).isEqualTo(ReportStatus.QUEUED);
        assertThat(jobs.findById(secondId).orElseThrow().status()).isEqualTo(ReportStatus.QUEUED);

        var worker = start("worker");
        assertThat(worker).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(worker.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(worker.getBeansOfType(HikariDataSource.class)).hasSize(1);
        assertThat(worker.getBeansOfType(ReportJobListener.class)).hasSize(1);
        assertThat(worker.getBeansOfType(ReportQueuePublisher.class)).isEmpty();
        assertThat(worker.getBeansOfType(ReportDispatcher.class)).isEmpty();
        assertThat(worker.getBeansOfType(ReportDispatchScheduler.class)).isEmpty();

        await(() -> jobs.findById(firstId).orElseThrow().status() == ReportStatus.COMPLETED
                && jobs.findById(secondId).orElseThrow().status() == ReportStatus.COMPLETED);
        assertThat(admin.getQueueInfo(queue).getConsumerCount()).isEqualTo(1);
        for (String id : List.of(firstId, secondId)) {
            var job = jobs.findById(id).orElseThrow();
            assertThat(job.attemptCount()).isEqualTo(1);
            assertThat(job.artifact().rowCount()).isEqualTo(5);
            var response = get(url(secondApi) + "/api/v1/reports/" + id + "/download");
            assertThat(response.statusCode()).isEqualTo(200);
            var csv = get(json.readTree(response.body()).path("downloadUrl").asText());
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.body()).startsWith("id,occurred_at,reference,amount,status\r\n")
                    .contains("At inclusive start", "Unicode: café").doesNotContain("At exclusive end");
        }
    }

    @AfterAll
    void stop() {
        try {
            for (int i = applications.size() - 1; i >= 0; i--) {
                if (!(applications.get(i) instanceof WebServerApplicationContext)) applications.get(i).close();
            }
            if (!applications.isEmpty()) {
                var api = applications.getFirst();
                var s3 = api.getBean(S3Client.class);
                for (var job : api.getBean(ReportJobRepository.class).findAll()) {
                    if (job.artifact() != null) s3.deleteObject(b -> b.bucket("reporting-exports").key(job.artifact().objectKey()));
                }
                var admin = new RabbitAdmin(api.getBean(RabbitTemplate.class));
                admin.deleteQueue(queue);
                admin.deleteExchange(exchange);
                api.getBean(MongoTemplate.class).getDb().drop();
            }
        } finally {
            for (int i = applications.size() - 1; i >= 0; i--) applications.get(i).close();
            http.close();
        }
    }

    private ConfigurableApplicationContext start(String profile) {
        String mongo = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        var args = new ArrayList<>(List.of("--spring.profiles.active=" + profile,
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.mongodb.uri=mongodb://" + mongo + "/roles_it_" + suffix,
                "--spring.rabbitmq.host=" + System.getenv().getOrDefault("RABBITMQ_TEST_HOST", "localhost"),
                "--spring.rabbitmq.port=" + System.getenv().getOrDefault("RABBITMQ_TEST_PORT", "5672"),
                "--spring.rabbitmq.username=" + System.getenv().getOrDefault("RABBITMQ_TEST_USERNAME", "reporting"),
                "--spring.rabbitmq.password=" + System.getenv().getOrDefault("RABBITMQ_TEST_PASSWORD", "reporting-local"),
                "--spring.rabbitmq.virtual-host=" + System.getenv().getOrDefault("RABBITMQ_TEST_VHOST", "reporting"),
                "--reporting.dispatch.queue=" + queue, "--reporting.dispatch.exchange=" + exchange,
                "--reporting.dispatch.poll-interval=100ms",
                "--reporting.storage.endpoint=http://127.0.0.1:" + System.getenv().getOrDefault("S3_TEST_PORT", "8333")));
        String mysqlPort = profile.equals("worker") ? System.getenv().getOrDefault("MYSQL_TEST_PORT", "3306") : "1";
        args.add("--reporting.worker.jdbc-url=jdbc:mysql://127.0.0.1:" + mysqlPort + "/reporting_source");
        var application = new SpringApplicationBuilder(ReportingServiceApplication.class).run(args.toArray(String[]::new));
        applications.add(application);
        return application;
    }

    private String submit(ConfigurableApplicationContext api) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url(api) + "/api/v1/reports"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\"}"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("reportId").asText();
    }

    private HttpResponse<String> get(String target) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String url(ConfigurableApplicationContext api) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) api).getWebServer().getPort();
    }

    private void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Deployment-role integration condition did not become true");
    }
}
