package com.akash.reporting.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.akash.reporting.model.ReportArtifact;
import com.akash.reporting.model.ReportFailure;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.model.WorkerClaim;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.bson.Document;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReportWorkerRepository {
    private final MongoTemplate mongo;

    public ReportWorkerRepository(MongoTemplate mongo) { this.mongo = mongo; }

    public Optional<WorkerClaim> claim(String reportId, Instant now, Duration leaseDuration) {
        var eligible = new Criteria().andOperator(Criteria.where("id").is(reportId),
                new Criteria().orOperator(Criteria.where("status").is(ReportStatus.QUEUED),
                        new Criteria().andOperator(Criteria.where("status").is(ReportStatus.RUNNING),
                                Criteria.where("worker.leaseUntil").lte(now))));
        String token = UUID.randomUUID().toString();
        // Update pipeline keeps the first start time and increments attempts in the same atomic claim.
        var fields = new Document("status", ReportStatus.RUNNING.name())
                .append("worker", new Document("token", token)
                        .append("leaseUntil", Date.from(now.plus(leaseDuration))))
                .append("firstStartedAt", new Document("$ifNull",
                        List.of("$firstStartedAt", Date.from(now))))
                .append("attemptCount", new Document("$add", List.of(
                        new Document("$ifNull", List.of("$attemptCount", 0)), 1)));
        var update = AggregationUpdate.from(
                List.of(context -> new Document("$set", fields)));
        var job = mongo.findAndModify(new Query(eligible), update,
                FindAndModifyOptions.options().returnNew(true), ReportJob.class);
        if (job == null) return Optional.empty();
        return Optional.of(new WorkerClaim(job.id(), token, job.startInclusive(), job.endExclusive()));
    }

    public boolean renew(WorkerClaim claim, Instant now, Duration duration) {
        return mongo.updateFirst(owned(claim, now), new Update().set("worker.leaseUntil", now.plus(duration)),
                ReportJob.class).getMatchedCount() == 1;
    }

    public boolean complete(WorkerClaim claim, Instant now, ReportArtifact artifact) {
        return mongo.updateFirst(owned(claim, now), new Update().set("status", ReportStatus.COMPLETED)
                .set("artifact", artifact).set("completedAt", now).unset("worker").unset("failure"),
                ReportJob.class).getModifiedCount() == 1;
    }

    public boolean fail(WorkerClaim claim, Instant now, ReportFailure failure) {
        return mongo.updateFirst(owned(claim, now), new Update().set("status", ReportStatus.FAILED)
                .set("failure", failure).set("completedAt", now).unset("worker"),
                ReportJob.class).getModifiedCount() == 1;
    }

    private Query owned(WorkerClaim claim, Instant now) {
        return new Query(Criteria.where("id").is(claim.reportId()).and("status").is(ReportStatus.RUNNING)
                .and("worker.token").is(claim.token()).and("worker.leaseUntil").gt(now));
    }
}
