package com.akash.reporting.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.akash.reporting.model.DispatchClaim;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.ReportStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
public class ReportDispatchRepository {

    private final MongoTemplate mongo;
    private volatile boolean indexReady;

    public ReportDispatchRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Optional<DispatchClaim> claimNext(Instant now, Duration leaseDuration) {
        ensureDispatchIndex();
        var eligible = new Criteria().andOperator(
                Criteria.where("dispatchPending").is(true),
                Criteria.where("status").is(ReportStatus.QUEUED),
                new Criteria().orOperator(Criteria.where("dispatch.leaseUntil").is(null),
                        Criteria.where("dispatch.leaseUntil").lte(now)),
                new Criteria().orOperator(Criteria.where("dispatch.nextAttemptAt").is(null),
                        Criteria.where("dispatch.nextAttemptAt").lte(now)));
        var query = new Query(eligible).with(Sort.by("submittedAt", "id"));
        String token = UUID.randomUUID().toString();
        var update = new Update().set("dispatch.token", token)
                .set("dispatch.leaseUntil", now.plus(leaseDuration))
                .inc("dispatch.attemptCount", 1);

        var job = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), ReportJob.class);
        return Optional.ofNullable(job).map(value -> new DispatchClaim(value.id(), token));
    }

    public boolean markPublished(DispatchClaim claim, Instant now) {
        var update = new Update().set("dispatchPending", false).set("dispatch.publishedAt", now)
                .unset("dispatch.token").unset("dispatch.leaseUntil")
                .unset("dispatch.nextAttemptAt").unset("dispatch.lastError");
        return mongo.updateFirst(ownedClaim(claim), update, ReportJob.class).getModifiedCount() == 1;
    }

    public boolean reschedule(DispatchClaim claim, Instant retryAt, String error) {
        var update = new Update().set("dispatch.nextAttemptAt", retryAt).set("dispatch.lastError", error)
                .unset("dispatch.token").unset("dispatch.leaseUntil");
        return mongo.updateFirst(ownedClaim(claim), update, ReportJob.class).getModifiedCount() == 1;
    }

    private Query ownedClaim(DispatchClaim claim) {
        return new Query(Criteria.where("id").is(claim.reportId())
                .and("dispatchPending").is(true).and("dispatch.token").is(claim.token()));
    }

    private void ensureDispatchIndex() {
        if (!indexReady) {
            mongo.indexOps(ReportJob.class).ensureIndex(new Index()
                    .on("submittedAt", Sort.Direction.ASC).on("id", Sort.Direction.ASC)
                    .partial(PartialIndexFilter.of(Criteria.where("dispatchPending").is(true)
                            .and("status").is(ReportStatus.QUEUED)))
                    .named("pending_report_dispatch"));
            indexReady = true;
        }
    }
}
