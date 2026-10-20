package com.akash.reporting.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.akash.reporting.ReportingServiceApplication;
import com.akash.reporting.config.DispatchProperties;
import com.akash.reporting.dto.CreateReportRequest;
import com.akash.reporting.exception.ReportPublishException;
import com.akash.reporting.messaging.ReportQueuePublisher;
import com.akash.reporting.repository.ReportDispatchRepository;
import com.akash.reporting.service.ReportService;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportDispatchIT {

    private final List<ConfigurableApplicationContext> applications = new ArrayList<>();
    private final String databaseName = "dispatch_api_it_" + UUID.randomUUID().toString().replace("-", "");
    private final String exchangeName = "dispatch.it.exchange." + UUID.randomUUID();
    private final String queueName = "dispatch.it.jobs." + UUID.randomUUID();
    private final String rabbitHost = System.getenv().getOrDefault("RABBITMQ_TEST_HOST", "localhost");
    private final int rabbitPort = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_TEST_PORT", "5672"));
    private final String rabbitUser = System.getenv().getOrDefault("RABBITMQ_TEST_USERNAME", "reporting");
    private final String rabbitPassword = System.getenv().getOrDefault("RABBITMQ_TEST_PASSWORD", "reporting-local");
    private final String rabbitVhost = System.getenv().getOrDefault("RABBITMQ_TEST_VHOST", "reporting");
    private HttpClient http;
    private MongoClient mongo;
    private MongoDatabase database;
    private Connection rabbit;
    private Channel channel;
    private String mongoUri;

    @BeforeEach
    void connect() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String address = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        mongoUri = "mongodb://" + address + "/" + databaseName
                + "?serverSelectionTimeoutMS=3000&connectTimeoutMS=2000&socketTimeoutMS=10000";
        mongo = MongoClients.create(mongoUri);
        database = mongo.getDatabase(databaseName);
        database.runCommand(new Document("ping", 1));
        var factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        factory.setUsername(rabbitUser);
        factory.setPassword(rabbitPassword);
        factory.setVirtualHost(rabbitVhost);
        factory.setConnectionTimeout(3000);
        rabbit = factory.newConnection("reporting-service-dispatch-integration-test");
        channel = rabbit.createChannel();
    }

    @AfterEach
    void cleanUp() throws Exception {
        applications.forEach(ConfigurableApplicationContext::close);
        if (rabbit != null && rabbit.isOpen()) {
            // Each test owns these unique resources; application queues are never purged.
            try (var cleanup = rabbit.createChannel()) {
                cleanup.queueDelete(queueName);
                cleanup.exchangeDelete(exchangeName);
            } finally {
                rabbit.close();
            }
        }
        if (mongo != null) {
            try {
                database.drop();
            } finally {
                mongo.close();
            }
        }
        if (http != null) {
            http.close();
        }
    }

    @Test
    void submitsAndPublishesPersistentJsonThenClearsPendingDispatch() throws Exception {
        var application = startApplication(rabbitPort, "100ms");
        String id = submit(application);

        await(() -> !stored(id).getBoolean("dispatchPending"));
        var message = channel.basicGet(queueName, true);
        assertMessage(message, id);
        var job = stored(id);
        assertThat(job.getString("status")).isEqualTo("QUEUED");
        assertThat(job.getInteger("attemptCount")).isZero();
        var dispatch = job.get("dispatch", Document.class);
        assertThat(dispatch.getDate("publishedAt")).isNotNull();
        assertThat(dispatch.getInteger("attemptCount")).isEqualTo(1);
        assertThat(dispatch.get("token")).isNull();
        assertThat(dispatch.get("leaseUntil")).isNull();
    }

    @Test
    void brokerOutageDoesNotRejectSubmissionAndRestartRecoversThePendingJob() throws Exception {
        var unavailable = startApplication(1, "100ms");
        String id = submit(unavailable);
        await(() -> stored(id).get("dispatch", Document.class).getString("lastError") != null);
        assertThat(stored(id).getBoolean("dispatchPending")).isTrue();

        unavailable.close();
        startApplication(rabbitPort, "100ms");

        await(() -> !stored(id).getBoolean("dispatchPending"));
        assertMessage(channel.basicGet(queueName, true), id);
    }

    @Test
    void twoApplicationInstancesShareDispatchWithoutConcurrentDuplicateClaims() throws Exception {
        var first = startApplication(rabbitPort, "100ms");
        startApplication(rabbitPort, "100ms");
        var ids = new HashSet<String>();
        for (int index = 0; index < 8; index++) {
            ids.add(submit(first));
        }
        await(() -> database.getCollection("report_jobs").countDocuments(eq("dispatchPending", false)) == ids.size());

        var received = new ArrayList<String>();
        GetResponse message;
        while ((message = channel.basicGet(queueName, true)) != null) {
            received.add(JsonPath.read(new String(message.getBody(), StandardCharsets.UTF_8), "$.reportId"));
        }
        assertThat(received).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void mandatoryPublicationDetectsAnUnroutableMessageEvenIfTheBrokerAcknowledgesIt() {
        var application = startApplication(rabbitPort, "1h");
        var properties = application.getBean(DispatchProperties.class);
        var unroutable = new DispatchProperties(true, properties.pollInterval(), properties.leaseDuration(),
                properties.confirmTimeout(), properties.retryDelay(), properties.batchSize(),
                properties.exchange(), properties.queue(), "unbound." + UUID.randomUUID());
        var publisher = new ReportQueuePublisher(application.getBean(RabbitTemplate.class), unroutable,
                application.getBean(JsonMapper.class));

        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID().toString()))
                .isInstanceOf(ReportPublishException.class).hasMessageContaining("without routing");
    }

    @Test
    void crashAfterConfirmationCanReplayTheSameIdAndStaleClaimsCannotComplete() throws Exception {
        var application = startApplication(rabbitPort, "1h");
        var service = application.getBean(ReportService.class);
        var job = service.create(new CreateReportRequest(java.time.LocalDate.parse("2026-10-01"),
                java.time.LocalDate.parse("2026-10-02")));
        var repository = application.getBean(ReportDispatchRepository.class);
        var publisher = application.getBean(ReportQueuePublisher.class);
        Instant now = Instant.now();
        var first = repository.claimNext(now, Duration.ofSeconds(2)).orElseThrow();
        publisher.publish(first.reportId());
        // Simulate a crash before MongoDB records the confirmation, then reclaim an expired lease.
        var second = repository.claimNext(now.plusSeconds(3), Duration.ofSeconds(2)).orElseThrow();
        publisher.publish(second.reportId());

        assertThat(repository.markPublished(first, now.plusSeconds(3))).isFalse();
        assertThat(repository.markPublished(second, now.plusSeconds(3))).isTrue();
        assertMessage(channel.basicGet(queueName, true), job.id());
        assertMessage(channel.basicGet(queueName, true), job.id());
        assertThat(channel.basicGet(queueName, true)).isNull();
        assertThat(stored(job.id()).getBoolean("dispatchPending")).isFalse();
    }

    private ConfigurableApplicationContext startApplication(int port, String pollInterval) {
        var application = new SpringApplicationBuilder(ReportingServiceApplication.class).run(
                "--server.port=0", "--spring.mongodb.uri=" + mongoUri,
                "--spring.rabbitmq.host=" + rabbitHost, "--spring.rabbitmq.port=" + port,
                "--spring.rabbitmq.username=" + rabbitUser, "--spring.rabbitmq.password=" + rabbitPassword,
                "--spring.rabbitmq.virtual-host=" + rabbitVhost, "--spring.rabbitmq.connection-timeout=250ms",
                "--reporting.dispatch.enabled=true", "--reporting.dispatch.poll-interval=" + pollInterval,
                "--reporting.dispatch.lease-duration=3s", "--reporting.dispatch.confirm-timeout=1s",
                "--reporting.dispatch.retry-delay=100ms", "--reporting.dispatch.exchange=" + exchangeName,
                "--reporting.dispatch.queue=" + queueName, "--spring.main.banner-mode=off");
        applications.add(application);
        return application;
    }

    private String submit(ConfigurableApplicationContext application) throws Exception {
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reports"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return JsonPath.read(response.body(), "$.reportId");
    }

    private Document stored(String id) {
        return database.getCollection("report_jobs").find(eq("_id", id)).first();
    }

    private void assertMessage(GetResponse message, String id) {
        assertThat(message).isNotNull();
        assertThat((String) JsonPath.read(new String(message.getBody(), StandardCharsets.UTF_8), "$.reportId")).isEqualTo(id);
        assertThat(message.getProps().getMessageId()).isEqualTo(id);
        assertThat(message.getProps().getDeliveryMode()).isEqualTo(2);
        assertThat(message.getProps().getContentType()).isEqualTo("application/json");
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as("dispatch condition within ten seconds").isTrue();
    }
}
