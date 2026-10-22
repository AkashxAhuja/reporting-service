package com.akash.reporting.integration;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import com.akash.reporting.ReportingServiceApplication;
import com.akash.reporting.dto.ReportRequestedMessage;
import com.akash.reporting.messaging.ReportQueuePublisher;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.repository.ReportJobRepository;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportWorkerIT {
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String queue = "reporting.worker.it." + suffix;
    private final String exchange = "reporting.worker.it.exchange." + suffix;
    private ConfigurableApplicationContext application;
    private String api;
    private String jdbcUrl;

    @BeforeAll void start() {
        String mongo = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        String mysqlPort = System.getenv().getOrDefault("MYSQL_TEST_PORT", "3306");
        String s3Port = System.getenv().getOrDefault("S3_TEST_PORT", "8333");
        jdbcUrl = "jdbc:mysql://127.0.0.1:" + mysqlPort + "/reporting_source";
        application = new SpringApplicationBuilder(ReportingServiceApplication.class).run(
                "--spring.profiles.active=local", "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.mongodb.uri=mongodb://" + mongo + "/worker_it_" + suffix,
                "--spring.rabbitmq.host=" + System.getenv().getOrDefault("RABBITMQ_TEST_HOST", "localhost"),
                "--spring.rabbitmq.port=" + System.getenv().getOrDefault("RABBITMQ_TEST_PORT", "5672"),
                "--reporting.dispatch.queue=" + queue, "--reporting.dispatch.exchange=" + exchange,
                "--reporting.dispatch.poll-interval=100ms", "--reporting.worker.enabled=true",
                "--reporting.worker.batch-size=500", "--reporting.worker.jdbc-url=" + jdbcUrl,
                "--reporting.storage.endpoint=http://127.0.0.1:" + s3Port,
                "--reporting.storage.part-size-bytes=5242880", "--reporting.storage.max-output-bytes=1073741824");
        api = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
    }

    @AfterAll void stop() {
        if (application == null) return;
        try {
            application.getBean(RabbitListenerEndpointRegistry.class).stop();
            var s3 = application.getBean(S3Client.class);
            for (var job : application.getBean(ReportJobRepository.class).findAll()) {
                if (job.artifact() != null) s3.deleteObject(b -> b.bucket("reporting-exports").key(job.artifact().objectKey()));
            }
            var admin = new RabbitAdmin(application.getBean(RabbitTemplate.class));
            admin.deleteQueue(queue); admin.deleteExchange(exchange);
            application.getBean(org.springframework.data.mongodb.core.MongoTemplate.class).getDb().drop();
        } finally { application.close(); }
    }

    @Test void exportsExactUtcRangeAndDownloadsValidCsvAcrossBatches() throws Exception {
        String id = submit("2026-10-01", "2026-10-02");
        var completed = await(id, "COMPLETED");
        assertThat(completed.path("rowCount").asLong()).isEqualTo(5);
        assertThat(completed.path("attemptCount").asInt()).isEqualTo(1);
        assertThat(completed.path("firstStartedAt").isNull()).isFalse();
        var downloaded = download(id);
        String csv = downloaded.body();
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(csv).isEqualTo("id,occurred_at,reference,amount,status\r\n"
                + "2,2026-10-01T00:00:00Z,At inclusive start,100.00,SETTLED\r\n"
                + "3,2026-10-01T12:00:00.123456Z,\"Order, with comma\",200.50,SETTLED\r\n"
                + "4,2026-10-01T12:00:00.123456Z,\"Customer \"\"quoted\"\"\",-25.00,REFUNDED\r\n"
                + "5,2026-10-01T18:00:00Z,\"Line one\nLine two\",0.00,PENDING\r\n"
                + "6,2026-10-01T23:59:59.999999Z,Unicode: café,42.10,SETTLED\r\n");
        assertThat(completed.path("byteSize").asLong()).isEqualTo(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String url = signedUrl(id);
        var unsigned = http.send(HttpRequest.newBuilder(URI.create(url.split("\\?")[0])).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unsigned.statusCode()).isEqualTo(403);
    }

    @Test void emptyResultProducesHeaderOnly() throws Exception {
        String id = submit("2026-10-10", "2026-10-11");
        assertThat(await(id, "COMPLETED").path("rowCount").asLong()).isZero();
        assertThat(download(id).body()).isEqualTo("id,occurred_at,reference,amount,status\r\n");
    }

    @Test void duplicateMessageDoesNotRegenerateCompletedReport() throws Exception {
        String id = submit("2026-10-02", "2026-10-03"); await(id, "COMPLETED");
        var repository = application.getBean(ReportJobRepository.class);
        ReportJob before = repository.findById(id).orElseThrow();
        application.getBean(ReportQueuePublisher.class).publish(id);
        Thread.sleep(1200);
        assertThat(repository.findById(id).orElseThrow()).isEqualTo(before);
    }

    @Test void downloadRequiresCompletedJob() throws Exception {
        var listeners = application.getBean(RabbitListenerEndpointRegistry.class);
        listeners.stop();
        try {
            String id = submit("2026-10-01", "2026-10-02");
            assertThat(get("/api/v1/reports/" + id + "/download").statusCode()).isEqualTo(409);
            assertThat(get("/api/v1/reports/" + UUID.randomUUID() + "/download").statusCode()).isEqualTo(404);
        } finally { listeners.start(); }
    }

    @Test void sourceFailureBecomesSafeTerminalFailure() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl + "?rewriteBatchedStatements=true", "root", "reporting-root-local");
                var statement = connection.createStatement()) {
            statement.execute("RENAME TABLE report_events TO report_events_temporarily_unavailable");
            try {
                String id = submit("2026-10-01", "2026-10-02");
                var failed = await(id, "FAILED");
                assertThat(failed.path("failure").path("code").asText()).isEqualTo("EXPORT_FAILED");
                assertThat(failed.toString()).doesNotContain("SQLException", "report_events", "jdbc:mysql");
                assertThat(get("/api/v1/reports/" + id + "/download").statusCode()).isEqualTo(409);
            } finally { statement.execute("RENAME TABLE report_events_temporarily_unavailable TO report_events"); }
        }
    }

    @Test void keysetPagingKeepsEqualTimestampsAcrossBatchBoundaries() {
        var settings = new com.akash.reporting.config.WorkerProperties(true, 2, Duration.ofSeconds(60),
                Duration.ofSeconds(10), Duration.ofMinutes(10), 30, jdbcUrl, "report_reader", "report-reader-local");
        var reader = new com.akash.reporting.repository.ReportEventRepository(
                application.getBean(org.springframework.jdbc.core.JdbcTemplate.class), settings);
        var start = java.time.Instant.parse("2026-10-01T00:00:00Z");
        var end = java.time.Instant.parse("2026-10-02T00:00:00Z");
        var first = reader.nextBatch(start, end, null);
        var second = reader.nextBatch(start, end, first.getLast());
        var third = reader.nextBatch(start, end, second.getLast());
        assertThat(first).extracting(com.akash.reporting.model.ReportEvent::id).containsExactly(2L, 3L);
        assertThat(second).extracting(com.akash.reporting.model.ReportEvent::id).containsExactly(4L, 5L);
        assertThat(third).extracting(com.akash.reporting.model.ReportEvent::id).containsExactly(6L);
        assertThat(reader.nextBatch(start, end, third.getLast())).isEmpty();
    }

    @Test void largeReportUsesMultipleUploadParts() throws Exception {
        // Test-only writes use root; the application has SELECT permission only.
        try (var connection = DriverManager.getConnection(jdbcUrl + "?rewriteBatchedStatements=true", "root", "reporting-root-local")) {
            try (var insert = connection.prepareStatement("INSERT INTO report_events VALUES (?, '2026-10-05 12:00:00', ?, 1.00, 'SETTLED')")) {
                for (int i = 0; i < 24000; i++) {
                    insert.setLong(1, 10000L + i); insert.setString(2, "x".repeat(250)); insert.addBatch();
                    if (i % 500 == 499) insert.executeBatch();
                }
            }
            try {
                String id = submit("2026-10-05", "2026-10-06");
                var job = await(id, "COMPLETED");
                assertThat(job.path("rowCount").asLong()).isEqualTo(24000);
                assertThat(job.path("byteSize").asLong()).isGreaterThan(5242880);
                String csv = download(id).body();
                assertThat(csv.lines().count()).isEqualTo(24001);
                assertThat(csv).contains("10000,2026-10-05T12:00:00Z", "33999,2026-10-05T12:00:00Z");
            } finally {
                try (var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM report_events WHERE id >= 10000"); }
            }
        }
    }

    private String submit(String start, String end) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/reports"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("reportId").asText();
    }
    private JsonNode await(String id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(100).toNanos();
        while (System.nanoTime() < deadline) {
            var value = json.readTree(get("/api/v1/reports/" + id).body());
            if (value.path("status").asText().equals(expected)) return value;
            if (value.path("status").asText().equals("FAILED")) throw new AssertionError(value.toString());
            Thread.sleep(100);
        }
        throw new AssertionError("Report did not reach " + expected);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String signedUrl(String id) throws Exception {
        var response = get("/api/v1/reports/" + id + "/download");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body()).path("downloadUrl").asText();
    }
    private HttpResponse<String> download(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(signedUrl(id))).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
