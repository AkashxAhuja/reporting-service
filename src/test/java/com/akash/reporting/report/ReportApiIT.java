package com.akash.reporting.report;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.akash.reporting.ReportingServiceApplication;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportApiIT {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String databaseName = "reporting_it_" + UUID.randomUUID().toString().replace("-", "");
    private ConfigurableApplicationContext application;
    private MongoClient mongo;
    private MongoDatabase database;
    private String mongoUri;
    private String baseUrl;

    @BeforeAll
    void start() {
        String address = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        mongoUri = "mongodb://" + address + "/" + databaseName
                + "?serverSelectionTimeoutMS=3000&connectTimeoutMS=2000&socketTimeoutMS=10000";
        mongo = MongoClients.create(mongoUri);
        database = mongo.getDatabase(databaseName);
        database.runCommand(new Document("ping", 1));
        startApplication(mongoUri);
    }

    @AfterAll
    void stop() {
        if (application != null) {
            application.close();
        }
        if (mongo != null) {
            try {
                database.drop();
            } finally {
                mongo.close();
            }
        }
        http.close();
    }

    @Test
    void acceptedJobAndPendingDispatchSurviveAnApplicationRestart() throws Exception {
        var created = submit("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\"}");
        assertThat(created.statusCode()).isEqualTo(202);
        String reportId = JsonPath.read(created.body(), "$.reportId");
        String statusUrl = JsonPath.read(created.body(), "$.statusUrl");
        assertThat(created.headers().firstValue("Location")).contains(statusUrl);

        var stored = database.getCollection("report_jobs").find(eq("_id", reportId)).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getBoolean("dispatchPending")).isTrue();
        assertThat(stored.getString("status")).isEqualTo("QUEUED");
        assertThat(stored.getDate("startInclusive"))
                .isEqualTo(Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(stored.getDate("endExclusive"))
                .isEqualTo(Date.from(Instant.parse("2026-10-02T00:00:00Z")));

        var concern = application.getBean(MongoClient.class).getDatabase(databaseName).getWriteConcern();
        assertThat(concern.getWString()).isEqualTo("majority");
        assertThat(concern.getJournal()).isTrue();

        application.close();
        startApplication(mongoUri);

        var fetched = http.send(HttpRequest.newBuilder(URI.create(baseUrl + statusUrl))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
        assertThat(database.getCollection("report_jobs").find(eq("_id", reportId)).first()
                .getBoolean("dispatchPending")).isTrue();
    }

    @Test
    void invalidRangesDoNotCreateDocuments() throws Exception {
        long before = database.getCollection("report_jobs").countDocuments();
        for (String body : new String[] {
                "{\"startDate\":\"2026-10-02\",\"endDate\":\"2026-10-01\"}",
                "{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-01\"}",
                "{\"startDate\":\"2024-01-01\",\"endDate\":\"2026-01-02\"}"
        }) {
            assertThat(submit(body).statusCode()).isEqualTo(400);
        }
        assertThat(database.getCollection("report_jobs").countDocuments()).isEqualTo(before);
    }

    @Test
    void missingReportReturnsNotFound() throws Exception {
        var response = http.send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/v1/reports/" + UUID.randomUUID())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void unreachableMongoReturnsServiceUnavailable() throws Exception {
        application.close();
        try {
            startApplication("mongodb://127.0.0.1:1/" + databaseName
                    + "?serverSelectionTimeoutMS=250&connectTimeoutMS=250");
            var response = submit("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\"}");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Location")).isEmpty();
            assertThat(response.body()).doesNotContain("127.0.0.1", "MongoSocketOpenException");
        } finally {
            application.close();
            startApplication(mongoUri);
        }
    }

    private void startApplication(String uri) {
        application = new SpringApplicationBuilder(ReportingServiceApplication.class).run(
                "--server.port=0", "--spring.mongodb.uri=" + uri, "--spring.main.banner-mode=off");
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> submit(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/reports"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
