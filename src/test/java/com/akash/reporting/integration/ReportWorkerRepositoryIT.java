package com.akash.reporting.integration;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.akash.reporting.model.*;
import com.akash.reporting.repository.ReportWorkerRepository;
import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportWorkerRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private MongoClient client;
    private MongoTemplate mongo;
    private ReportWorkerRepository repository;

    @BeforeAll void start() {
        String address = System.getenv().getOrDefault("MONGODB_TEST_ADDRESS", "localhost:27017");
        client = MongoClients.create("mongodb://" + address + "/?serverSelectionTimeoutMS=3000");
        mongo = new MongoTemplate(client, "worker_repository_it_" + UUID.randomUUID().toString().replace("-", ""));
        mongo.executeCommand(new Document("ping", 1));
        repository = new ReportWorkerRepository(mongo);
    }
    @BeforeEach void clear() { mongo.remove(new Query(), ReportJob.class); }
    @AfterAll void stop() { if (mongo != null) mongo.getDb().drop(); if (client != null) client.close(); }

    @Test void competingWorkersHaveOneOwner() throws Exception {
        var job = insert(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { barrier.await(); return repository.claim(job.id(), NOW, Duration.ofSeconds(3)); });
            var two = executor.submit(() -> { barrier.await(); return repository.claim(job.id(), NOW, Duration.ofSeconds(3)); });
            assertThat(java.util.stream.Stream.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS))
                    .flatMap(Optional::stream).toList()).hasSize(1);
        }
        var saved = mongo.findById(job.id(), ReportJob.class);
        assertThat(saved.firstStartedAt()).isEqualTo(NOW);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.dispatchPending()).isTrue(); // A consumer can receive before the publisher clears its marker.
    }

    @Test void renewalAndReclaimFenceStaleOwnersAndPreserveFirstStart() {
        var job = insert(); var lease = Duration.ofSeconds(3);
        var first = repository.claim(job.id(), NOW, lease).orElseThrow();
        assertThat(repository.renew(first, NOW.plusSeconds(2), lease)).isTrue();
        assertThat(repository.claim(job.id(), NOW.plusSeconds(4), lease)).isEmpty();
        assertThat(repository.renew(first, NOW.plusSeconds(5), lease)).isFalse();
        var second = repository.claim(job.id(), NOW.plusSeconds(5), lease).orElseThrow();
        var artifact = new ReportArtifact("attempt.csv", "report.csv", 3, 123);
        assertThat(repository.complete(first, NOW.plusSeconds(5), artifact)).isFalse();
        assertThat(repository.fail(first, NOW.plusSeconds(5), new ReportFailure("OLD", "old"))).isFalse();
        assertThat(repository.complete(second, NOW.plusSeconds(5), artifact)).isTrue();
        assertThat(repository.claim(job.id(), NOW.plusSeconds(9), lease)).isEmpty();
        var saved = mongo.findById(job.id(), ReportJob.class);
        assertThat(saved.status()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(saved.firstStartedAt()).isEqualTo(NOW);
        assertThat(saved.attemptCount()).isEqualTo(2);
        assertThat(saved.artifact()).isEqualTo(artifact);
        assertThat(saved.worker()).isNull();
    }

    @Test void confirmedFailureIsTerminal() {
        var job = insert(); var claim = repository.claim(job.id(), NOW, Duration.ofSeconds(3)).orElseThrow();
        assertThat(repository.fail(claim, NOW.plusSeconds(1), new ReportFailure("EXPORT_FAILED", "Safe message"))).isTrue();
        assertThat(repository.claim(job.id(), NOW.plusSeconds(5), Duration.ofSeconds(3))).isEmpty();
        assertThat(mongo.findById(job.id(), ReportJob.class).failure().message()).isEqualTo("Safe message");
    }

    private ReportJob insert() {
        return mongo.insert(new ReportJob(UUID.randomUUID().toString(), NOW.minusSeconds(86400), NOW,
                "CSV", ReportStatus.QUEUED, NOW, null, null, 0, true, DispatchMetadata.pending(), null, null, null));
    }
}
