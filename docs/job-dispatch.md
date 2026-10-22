# Milestone 2: recoverable dispatch to RabbitMQ

This document records milestone 2 with worker processing disabled. Milestone 3 adds an opt-in consumer and CSV downloads; see [the current generation guide](report-generation.md).

## Behavior

Milestone 1's POST and GET contracts remain unchanged. POST saves a queued job plus its pending-dispatch marker and returns `202` after MongoDB acknowledges the insert. A background dispatcher publishes the job ID to RabbitMQ. A successful publish does not mean a worker has started: status remains `QUEUED`, worker `attemptCount` and API `retryCount` remain zero, and processing timestamps remain unset.

1. Poll for pending, queued jobs whose dispatch lease and retry delay have expired.
2. Atomically claim one job with MongoDB `findAndModify`, a unique token, and a lease deadline. Increment `dispatch.attemptCount`.
3. Send persistent JSON `{"reportId":"..."}` to the durable direct exchange with mandatory routing. The AMQP message ID is the report ID and its type is `report.requested.v1`.
4. Wait for a positive correlated publisher confirmation. A negative confirmation, timeout, connection failure, or returned/unroutable message is a failed publication.
5. On confirmed, routed publication, update MongoDB only if the same claim token is still current: set `dispatchPending=false`, record `dispatch.publishedAt`, and remove the lease, token, retry deadline, and last error.
6. On publication failure, keep the job pending, record a safe error and retry deadline, and release the claim. Stop the current polling cycle to avoid repeatedly hitting an unavailable broker.
7. If the application crashes or a metadata update cannot be confirmed, leave recovery to the stored state and lease expiry. Another instance can reclaim the job. A stale owner cannot update a newer token's claim.

Claims are taken one at a time, with at most ten sequential jobs per polling cycle by default. Instances share work through MongoDB; there is no in-memory claim list. Eligible jobs are ordered by submission time and ID. A partial index covers pending queued jobs and that ordering. Index creation is lazy and retried on metadata failure, so MongoDB or RabbitMQ unavailability does not make successful application startup a prerequisite for later recovery.

## Delivery and failure guarantees

MongoDB acceptance and RabbitMQ publication are separate operations. If RabbitMQ accepts a message but the application crashes before clearing the MongoDB marker, the message can be published again after lease expiry. The same applies when a confirmation is lost. Publication is **at least once**; the future worker must handle repeated report IDs safely.

Publisher confirms acknowledge the broker's handling of a message. They do not acknowledge report processing. Persistent messages and durable queue/exchange declarations retain queued work across a normal broker restart; the local single broker does not provide cluster failover. [RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms), [reliability guidance](https://www.rabbitmq.com/docs/reliability), and [Spring confirms and returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html#template-confirms) describe these boundaries.

The application enforces correlated confirms, publisher returns, and mandatory publication. It clears pending dispatch only after an acknowledgement with no returned message. Dispatch failures retry with a fixed configurable delay and do not change worker attempts or mark the report `FAILED`. Worker retries and dead-letter handling are later milestones.

Lease times use the application's UTC clock; service hosts must keep clocks synchronized. The default 30-second lease exceeds the five-second confirmation wait, but a stalled process or network can still outlive its lease. Token checks prevent stale metadata writes; they do not make publication exactly once. Longer stalls may produce duplicate messages safely identified by the same report ID.

## Stored dispatch metadata

| MongoDB field | Meaning |
| --- | --- |
| `dispatchPending` | True until confirmed publication has been recorded. |
| `dispatch.token` | Unique token for the current claim, removed when it is released. |
| `dispatch.leaseUntil` | UTC deadline after which a claim can be recovered. |
| `dispatch.attemptCount` | Number of dispatch claims, separate from worker attempts. |
| `dispatch.nextAttemptAt` | Earliest retry time after a failed publication. |
| `dispatch.lastError` | Safe failure summary; full exception details are logged. |
| `dispatch.publishedAt` | UTC time at which confirmed publication was recorded. |

Milestone 1 documents with the old Java `_class` name and no `dispatch` object remain readable and claimable. New documents use the stable `report-job` type alias. Existing API responses do not expose internal dispatch metadata. No data migration or database reset is required.

## Start the local dependencies

Run in Terminal. Homebrew, the Docker CLI, Docker Compose, and Colima are the command-line setup described earlier; neither a native MongoDB/RabbitMQ server nor Docker Desktop is required.

```sh
colima start --runtime docker --vm-type vz --cpu 2 --memory 4 --disk 20
docker context use colima
docker version
docker compose version

cd /Users/akash/GitWorkspace/reporting-service

# Explicit image download; Compose also downloads missing images automatically.
docker pull rabbitmq:4.4.0-management

# Start MongoDB and RabbitMQ, retaining the existing MongoDB volume.
docker compose up -d --wait mongo rabbitmq
docker compose ps

# Verify both servers.
docker compose exec mongo mongosh --quiet --eval 'db.adminCommand({ ping: 1 })'
docker compose exec rabbitmq rabbitmq-diagnostics -q check_running
```

The Compose RabbitMQ service uses a stable hostname and a named data volume. It creates virtual host `reporting`, username `reporting`, and password `reporting-local` on its initial startup. AMQP is exposed on `127.0.0.1:5672`; the management UI is exposed on `127.0.0.1:15672`. The credentials are development defaults. [RabbitMQ's installation guide](https://www.rabbitmq.com/docs/download) documents management-enabled container images.

## Start the updated application

Stop any old application process with Ctrl+C. Package names changed, so use a clean build on the first run:

```sh
cd /Users/akash/GitWorkspace/reporting-service
./mvnw clean spring-boot:run
```

Leave that terminal running. The dispatcher is enabled by default and also picks up older pending milestone 1 jobs. For metadata-only operation, use `./mvnw clean spring-boot:run -Dspring-boot.run.arguments=--reporting.dispatch.enabled=false`. Named deployment roles are described in [the role guide](deployment-roles.md).

## Test acceptance, publication, and lookup

In a second terminal:

```sh
curl -i http://localhost:8081/api/v1/reports \
  -H 'Content-Type: application/json' \
  -d '{"startDate":"2026-10-01","endDate":"2026-10-02"}'
```

Expect `202 Accepted`. Copy the returned report ID, then run:

```sh
curl -i http://localhost:8081/api/v1/reports/REPLACE_WITH_REPORT_ID
```

Expect `200` and `QUEUED`. The Postman workspace remains outside the repository under `/Users/akash/GitWorkspace/postman`; its existing create/get requests still work.

### Inspect the MongoDB job

```sh
cd /Users/akash/GitWorkspace/reporting-service
docker compose exec mongo mongosh reporting
```

Inside the MongoDB shell:

```javascript
db.report_jobs.findOne({ _id: "REPLACE_WITH_REPORT_ID" })
```

After successful dispatch, expect `dispatchPending: false`, a populated `dispatch.publishedAt`, and `dispatch.attemptCount` of at least one. Worker `attemptCount` stays zero. The token and lease are removed. The transition may happen before you inspect the document because publication runs independently of the API response. Type `exit` to leave the shell.

### Inspect RabbitMQ in the GUI

1. Open [http://localhost:15672](http://localhost:15672).
2. Sign in with **reporting** / **reporting-local**.
3. Select virtual host **reporting** and open **Queues and Streams**.
4. Open **reporting.reports.jobs**. The queue appears after the application first connects to publish a pending job. **Ready** messages accumulate; **Consumers** is zero in this milestone.
5. Expand **Get messages**, choose an acknowledgement/requeue option that puts the inspected message back on the queue, and request one message. The payload contains the report ID. Leave requeue enabled to keep that job available for the future worker.

The **Exchanges** view contains **reporting.reports**, bound to the queue using **report.requested**. [RabbitMQ management documentation](https://www.rabbitmq.com/docs/management) explains the web UI.

For a command-line queue check:

```sh
docker compose exec rabbitmq rabbitmqctl list_queues -p reporting name messages_ready messages_unacknowledged consumers
```

## Test broker outage and recovery

Keep MongoDB and the application running:

```sh
cd /Users/akash/GitWorkspace/reporting-service
docker compose stop rabbitmq
```

Submit another POST. It should still return `202` because acceptance depends on MongoDB. Inspect that new job in MongoDB: it stays pending, and a dispatch failure eventually sets `dispatch.lastError` and `dispatch.nextAttemptAt`. The application logs the failure.

Restore the broker:

```sh
docker compose up -d --wait rabbitmq
```

Within the retry delay plus polling and broker reconnect time, the same job should become `dispatchPending=false` and appear in the queue. No new POST is needed. You can also restart the application while the broker is stopped, then restore RabbitMQ, to check recovery of the saved dispatch intention.

For broker restart persistence, note the queue's Ready count, restart RabbitMQ, and check it again once healthy:

```sh
docker compose restart rabbitmq
docker compose up -d --wait rabbitmq
docker compose exec rabbitmq rabbitmqctl list_queues -p reporting name messages_ready
```

With no worker consuming and no new submissions, queued messages should remain available across the restart.

## Test two application instances

With the first application still listening on 8081, start another terminal:

```sh
cd /Users/akash/GitWorkspace/reporting-service
PORT=8082 ./mvnw spring-boot:run -Dspring-boot.run.profiles=api
```

Submit through either port. Both instances share MongoDB claims and the same RabbitMQ queue. Integration tests check this path and explicitly test that an expired owner's updates cannot alter a replacement claim.

## Logs, automated tests, and shutdown

Application dispatch logs appear in the terminal running Spring Boot. Server logs are available with:

```sh
docker compose logs --tail=100 mongo rabbitmq
```

Run unit/MVC tests without dependencies:

```sh
./mvnw clean test
```

Run the full integration build with both services healthy:

```sh
docker compose up -d --wait mongo rabbitmq
./mvnw -Pintegration clean verify
```

Integration tests use dedicated random databases, exchanges, and queues and remove those resources afterward. They never purge `reporting.reports.jobs` or drop the application database. Alternative test addresses and credentials are listed in the README.

When finished, stop each application with Ctrl+C, then:

```sh
docker compose stop mongo rabbitmq
colima stop
```

These commands preserve the service volumes for the next session.
