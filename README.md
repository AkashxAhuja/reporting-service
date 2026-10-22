# Reporting service

A reporting service for concurrent date-range requests over a single MySQL table containing approximately 200 million rows across two years.

The [high-level design](docs/high-level-design.md) describes the proposed architecture. The [Mermaid source](docs/diagrams/reporting-service.mmd) remains editable. The [submission LLD](docs/job-submission.md) covers milestone 1, and the [dispatch LLD and local test guide](docs/job-dispatch.md) covers milestone 2.

Current stage: **end-to-end CSV generation and downloading**. A worker consumes RabbitMQ jobs, reads MySQL in bounded batches, uploads CSV to S3-compatible storage, and saves completion in MongoDB. The [milestone 3 implementation and local test guide](docs/report-generation.md) covers the full flow, package responsibilities, failure behavior, GUI access and commands.

## Run locally

Requirements: Java 21 and Docker with Docker Compose. On macOS, Colima provides the Docker runtime from the command line. The Maven wrapper downloads Maven 3.9.16 on first use. The application uses Spring Boot 4.1.1, MongoDB 8.0.20, and RabbitMQ 4.4.0 with its management UI.

```sh
colima start --runtime docker --vm-type vz --cpu 2 --memory 4 --disk 20
docker context use colima
docker compose pull
docker compose up -d --wait mongo rabbitmq mysql storage
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=local
```

The API in the local role listens on `127.0.0.1:8081`. MongoDB, RabbitMQ, MySQL and SeaweedFS expose their ports only on loopback and use named volumes. A plain startup defaults to the `api` role with generation disabled. The command above selects `local`, which runs one API, dispatcher and worker together. For separate instances, use the [deployment roles guide](docs/deployment-roles.md). Stopping containers or running `docker compose down` preserves their volumes. The clean build removes stale classes from the milestone 1 package layout. Stop the old application process before starting the updated application.

For an existing MongoDB or RabbitMQ server, configure the connection settings below. Neither server requires a native macOS installation when using the supplied containers.

RabbitMQ's GUI is at [http://localhost:15672](http://localhost:15672). Sign in with username **reporting** and password **reporting-local**, select virtual host **reporting**, then open **Queues and Streams → reporting.reports.jobs** after submitting a report. The queue is declared when the application first connects to publish. With the worker enabled, messages are consumed and reports move through RUNNING to COMPLETED or FAILED. For payload inspection, select a requeue-enabled option under **Get messages**.

The full [milestone 2 guide](docs/job-dispatch.md) includes service checks, MongoDB inspection, outage/restart testing, logs, and shutdown commands.

```sh
curl -i http://localhost:8081/api/v1/reports \
  -H 'Content-Type: application/json' \
  -d '{"startDate":"2026-10-01","endDate":"2026-10-02"}'
```

The response is `202 Accepted`, with a `Location` header and a body like:

```json
{
  "reportId": "778048b2-3b55-4318-b8d1-a7dc4225bc2b",
  "startDate": "2026-10-01",
  "endDate": "2026-10-02",
  "format": "CSV",
  "status": "QUEUED",
  "submittedAt": "2026-10-19T08:00:00Z",
  "firstStartedAt": null,
  "completedAt": null,
  "attemptCount": 0,
  "retryCount": 0,
  "statusUrl": "/api/v1/reports/778048b2-3b55-4318-b8d1-a7dc4225bc2b",
  "rowCount": null,
  "byteSize": null,
  "downloadUrl": null,
  "failure": null
}
```

Use the returned ID to retrieve the saved job:

```sh
curl -i http://localhost:8081/api/v1/reports/REPLACE_WITH_REPORT_ID
```

The dispatcher normally publishes within a polling interval. In MongoDB, `dispatchPending` changes from `true` to `false` after a routed publication is confirmed, and `dispatch.publishedAt` is recorded. With an enabled worker, GET eventually reports `COMPLETED` with row count, byte size and a download endpoint. Without a worker, jobs remain `QUEUED`. Starting the updated application also dispatches pending jobs created during milestone 1.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MONGODB_URI` | `mongodb://localhost:27017/reporting?serverSelectionTimeoutMS=3000&connectTimeoutMS=2000&socketTimeoutMS=10000` | Metadata connection and driver deadlines. |
| `PORT` | `8081` | HTTP port for API/local roles. The worker role has no HTTP server. |
| `REPORT_MAX_RANGE_DAYS` | `731` | Maximum number of days in the requested half-open interval. Must be positive. |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | AMQP broker address. Port 15672 is for the management UI. |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `reporting` / `reporting-local` | Local development credentials. |
| `RABBITMQ_VHOST` | `reporting` | Broker virtual host created by Compose. |
| `SPRING_PROFILES_ACTIVE` | `api` when unset | Choose one role: `api`, `worker`, or `local`. See the [role guide](docs/deployment-roles.md). |
| `REPORT_DISPATCH_ENABLED` | `true` in base settings | Legacy base setting; named role profiles select dispatch and worker activation together. |
| `REPORT_DISPATCH_POLL_INTERVAL` | `1s` | Delay between completed polling cycles. |
| `REPORT_DISPATCH_LEASE_DURATION` | `30s` | Duration of a dispatch claim before another instance can reclaim it. |
| `REPORT_DISPATCH_CONFIRM_TIMEOUT` | `5s` | Maximum wait for a broker confirmation after sending. Must be shorter than the lease. |
| `REPORT_DISPATCH_RETRY_DELAY` | `5s` | Minimum delay before retrying a failed publication. |
| `REPORT_DISPATCH_BATCH_SIZE` | `10` | Maximum jobs processed sequentially per polling cycle, from 1 to 100. |
| `REPORT_DISPATCH_EXCHANGE` | `reporting.reports` | Durable direct exchange. |
| `REPORT_DISPATCH_QUEUE` | `reporting.reports.jobs` | Durable job queue. |
| `REPORT_DISPATCH_ROUTING_KEY` | `report.requested` | Routing key binding the exchange to the queue. |

The application enforces majority write acknowledgement, journaling, and a five-second write-concern timeout, including when a custom MongoDB URI is supplied.

Dates use `YYYY-MM-DD`. `startDate` is inclusive and `endDate` is exclusive; both boundaries are interpreted as midnight UTC. The example exports October 1 only. Output format is fixed to CSV. Additional JSON fields are rejected.

## Verification

Service, MVC, publisher, dispatcher, and configuration tests run without external services:

```sh
./mvnw clean test
```

Integration tests require MongoDB, RabbitMQ, MySQL and the S3-compatible store and start application instances on ephemeral HTTP ports:

```sh
docker compose up -d --wait mongo rabbitmq mysql storage
./mvnw -Pintegration clean verify
```

They cover two API instances sharing one headless worker, role isolation, milestone 1 behavior, competing claims, stale owners, delayed publication retries, legacy document compatibility, persistent messages, unroutable returns, broker outage recovery, two API instances sharing dispatch work, and the duplicate-delivery window after a crash. Tests own unique databases, exchanges, and queues; they do not purge application queues. `MONGODB_TEST_ADDRESS` overrides `localhost:27017`. RabbitMQ overrides are `RABBITMQ_TEST_HOST`, `RABBITMQ_TEST_PORT`, `RABBITMQ_TEST_USERNAME`, `RABBITMQ_TEST_PASSWORD`, and `RABBITMQ_TEST_VHOST`, with defaults matching Compose.

## Package layout

Production classes live under `com.akash.reporting`:

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP endpoints. |
| `dto` | API request/response objects and the queue message contract. |
| `model` | Persistent report and dispatch metadata. |
| `repository` | Job persistence, atomic dispatch claims, and guarded updates. |
| `service` | Submission, dispatch, worker lifecycle and downloading. |
| `export` | Bounded CSV writing. |
| `storage` | S3 multipart uploads, cleanup and signed URLs. |
| `messaging` | Persistent publication, confirms/returns and manual-ack consumption. |
| `scheduler` | Periodic dispatch trigger. |
| `config` | Validated settings, UTC clock, write concerns, and broker topology. |
| `exception` | Application exceptions and HTTP error mapping. |

## Milestone 3 review and testing

Follow [the generation guide](docs/report-generation.md) to submit the seeded October 1 report (five rows), poll completion, fetch a signed download URL, and inspect MySQL/MongoDB/RabbitMQ/stored files. Additional configuration is documented there. Tests use the dedicated local source fixture; do not run worker integration tests against a shared source database.

## Next milestone

Milestone 4 adds durable checkpoints/resume, bounded delayed worker retries, DLQ handling, and reconciliation/cleanup for abandoned attempts. Global query coordination and fairness follow. Authentication and ownership remain required before exposing this local development API.
