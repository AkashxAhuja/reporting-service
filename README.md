# Reporting service

A reporting service for concurrent date-range requests over a single MySQL table containing approximately 200 million rows across two years.

The [high-level design](docs/high-level-design.md) describes the proposed architecture. The [Mermaid source](docs/diagrams/reporting-service.mmd) remains editable. The [first milestone LLD](docs/job-submission.md) describes the implemented contract, persistence, and failure behavior.

Current stage: **durable report submission and status retrieval**. Accepted jobs remain `QUEUED` with a pending-dispatch marker. Dispatch, report generation, and downloads are subsequent milestones.

## Run locally

Requirements: Java 21 and Docker with Docker Compose. The Maven wrapper downloads Maven 3.9.16 on first use; a separate Maven installation is unnecessary. The application uses Spring Boot 4.1.1 and MongoDB 8.0.20.

```sh
docker compose up -d --wait mongo
./mvnw spring-boot:run
```

The API listens on `127.0.0.1:8080`. Local MongoDB is exposed only on loopback and stores data in a named Docker volume. Stopping the application or running `docker compose down` preserves submitted jobs. Removing the volume removes that data.

If MongoDB is already running locally, Docker is optional. Set `MONGODB_URI` if its address or database differs from the defaults.

```sh
curl -i http://localhost:8080/api/v1/reports \
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
  "statusUrl": "/api/v1/reports/778048b2-3b55-4318-b8d1-a7dc4225bc2b"
}
```

Use the returned ID to retrieve the saved job:

```sh
curl -i http://localhost:8080/api/v1/reports/REPLACE_WITH_REPORT_ID
```

Restart the application and repeat the GET to verify that MongoDB retains the job. No worker is running in this milestone, so the status remains `QUEUED`.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MONGODB_URI` | `mongodb://localhost:27017/reporting?serverSelectionTimeoutMS=3000&connectTimeoutMS=2000&socketTimeoutMS=10000` | Metadata connection and driver deadlines. |
| `PORT` | `8080` | HTTP port. |
| `REPORT_MAX_RANGE_DAYS` | `731` | Maximum number of days in the requested half-open interval. Must be positive. |

The application enforces majority write acknowledgement, journaling, and a five-second write-concern timeout, including when a custom MongoDB URI is supplied.

Dates use `YYYY-MM-DD`. `startDate` is inclusive and `endDate` is exclusive; both boundaries are interpreted as midnight UTC. The example exports October 1 only. Output format is fixed to CSV. Additional JSON fields are rejected.

## Verification

Service and MVC tests run without MongoDB or Docker:

```sh
./mvnw test
```

Integration tests require a real local MongoDB and start the API on ephemeral ports:

```sh
docker compose up -d --wait mongo
./mvnw -Pintegration verify
```

They verify actual document contents, pending dispatch, UTC boundaries, acknowledged/journaled write settings, application restart recovery, rejected ranges, unknown IDs, and a disconnected database returning `503`. Each run uses a unique test database and drops only that database afterward. If MongoDB uses another local address, set `MONGODB_TEST_ADDRESS`, for example `localhost:27018`.

## Next milestone

Implement the dispatcher that claims pending jobs, publishes their IDs to RabbitMQ, and clears the pending marker only after publisher confirmation. Then add bounded MySQL queries, durable output/checkpoints, local S3 storage, and the download endpoint.
