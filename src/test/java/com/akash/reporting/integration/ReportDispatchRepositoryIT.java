package com.akash.reporting.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.akash.reporting.model.DispatchMetadata;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.repository.ReportDispatchRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportDispatchRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(2);
    private MongoClient client;
    private MongoTemplate mongo;
    private ReportDispatchRepository repository;

    @BeforeAll
    void start() {
        String address = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        client = MongoClients.create("mongodb://" + address + "/?serverSelectionTimeoutMS=3000");
        mongo = new MongoTemplate(client, "dispatch_repository_it_" + UUID.randomUUID().toString().replace("-", ""));
        mongo.executeCommand(new Document("ping", 1));
        repository = new ReportDispatchRepository(mongo);
    }

    @BeforeEach
    void clearJobs() {
        mongo.remove(new Query(), ReportJob.class);
    }

    @AfterAll
    void stop() {
        try {
            if (mongo != null) {
                mongo.getDb().drop();
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void concurrentDispatchersCannotClaimTheSameActiveLease() throws Exception {
        insertJob(ReportStatus.QUEUED);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return repository.claimNext(NOW, LEASE);
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return repository.claimNext(NOW, LEASE);
            });
            var claims = java.util.stream.Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .flatMap(Optional::stream).toList();
            assertThat(claims).hasSize(1);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerCannotUpdateIt() {
        var job = insertJob(ReportStatus.QUEUED);
        var first = repository.claimNext(NOW, LEASE).orElseThrow();
        assertThat(repository.claimNext(NOW.plusSeconds(1), LEASE)).isEmpty();
        var second = repository.claimNext(NOW.plusSeconds(3), LEASE).orElseThrow();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(repository.markPublished(first, NOW.plusSeconds(3))).isFalse();
        assertThat(repository.reschedule(first, NOW.plusSeconds(30), "stale failure")).isFalse();
        assertThat(repository.markPublished(second, NOW.plusSeconds(3))).isTrue();
        var stored = mongo.findById(job.id(), ReportJob.class);
        assertThat(stored.dispatchPending()).isFalse();
        assertThat(stored.dispatch().attemptCount()).isEqualTo(2);
        assertThat(stored.dispatch().token()).isNull();
        assertThat(stored.dispatch().leaseUntil()).isNull();
        assertThat(stored.attemptCount()).isZero();
        assertThat(stored.status()).isEqualTo(ReportStatus.QUEUED);
    }

    @Test
    void failedPublicationRemainsPendingUntilItsRetryTime() {
        var job = insertJob(ReportStatus.QUEUED);
        var claim = repository.claimNext(NOW, LEASE).orElseThrow();

        assertThat(repository.reschedule(claim, NOW.plusSeconds(5), "No confirmation.")).isTrue();
        assertThat(repository.claimNext(NOW.plusSeconds(4), LEASE)).isEmpty();
        var pending = mongo.findById(job.id(), ReportJob.class);
        assertThat(pending.dispatchPending()).isTrue();
        assertThat(pending.dispatch().lastError()).isEqualTo("No confirmation.");
        assertThat(repository.claimNext(NOW.plusSeconds(5), LEASE)).isPresent();
    }

    @Test
    void milestoneOneDocumentsRemainReadableAndDispatchableAfterPackageChanges() {
        String id = UUID.randomUUID().toString();
        mongo.getCollection("report_jobs").insertOne(new Document("_id", id)
                .append("_class", "com.akash.reporting.report.ReportJob")
                .append("startInclusive", Date.from(NOW.minusSeconds(86400)))
                .append("endExclusive", Date.from(NOW)).append("format", "CSV")
                .append("status", "QUEUED").append("submittedAt", Date.from(NOW))
                .append("attemptCount", 0).append("dispatchPending", true));

        assertThat(mongo.findById(id, ReportJob.class).id()).isEqualTo(id);
        var claim = repository.claimNext(NOW, LEASE).orElseThrow();
        assertThat(claim.reportId()).isEqualTo(id);
        assertThat(mongo.findById(id, ReportJob.class).dispatch().attemptCount()).isEqualTo(1);
        assertThat(repository.markPublished(claim, NOW)).isTrue();
    }

    @Test
    void onlyQueuedPendingJobsCanBeClaimed() {
        insertJob(ReportStatus.RUNNING);

        assertThat(repository.claimNext(NOW, LEASE)).isEmpty();
    }

    private ReportJob insertJob(ReportStatus status) {
        return mongo.insert(new ReportJob(UUID.randomUUID().toString(), NOW.minusSeconds(86400), NOW,
                "CSV", status, NOW, null, null, 0, true, DispatchMetadata.pending()));
    }
}
