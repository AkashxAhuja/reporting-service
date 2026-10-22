# Reporting Service

An asynchronous service that accepts a date range, generates a CSV from a single MySQL table, and provides a temporary download URL. MongoDB stores job status, RabbitMQ queues work, and S3 stores completed files.

The design targets a table with 200 million records across two years, using the existing infrastructure and a small number of service instances. Completion time depends on the requested data, queued work and available database capacity.

## Design

- [High-level design](docs/HLD.md): architecture, deployment roles and design decisions.
- [Low-level design](docs/LLD.md): classes, APIs, data model, queries and failure handling.

## Run locally

Requirements: Java 21 and Docker with Docker Compose. The Maven wrapper downloads Maven on first use. On macOS, Colima can provide the Docker runtime without Docker Desktop:

```sh
colima start --runtime docker --vm-type vz --cpu 2 --memory 4 --disk 20
docker context use colima
```

From the project root, start dependencies and the application:

```sh
docker compose up -d --wait mongo rabbitmq mysql storage
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=local
```

The API is available at `http://localhost:8081`. The `local` profile runs the API, dispatcher and worker together. On first initialization of a fresh MySQL volume, Compose loads the schema, sample rows and SELECT-only application user from [the initialization script](docker/mysql/01-report-events.sql). Existing volumes retain their data.

| Dependency | Local address | Local credentials |
| --- | --- | --- |
| MongoDB | `mongodb://localhost:27017/reporting` | No authentication in local Compose. |
| RabbitMQ | AMQP: `localhost:5672`; [management UI](http://localhost:15672) | `reporting` / `reporting-local`; virtual host `reporting`. |
| MySQL | `localhost:3306/reporting_source` | `report_reader` / `report-reader-local` (SELECT only). |
| S3-compatible storage (SeaweedFS) | S3: `http://localhost:8333`; [file browser](http://localhost:8888) | Access key `reporting`; secret `reporting-local-storage`; bucket `reporting-exports`. |

These are local defaults. Configure service endpoints and credentials for your deployment. Published dependency ports and the local API bind to loopback.

## Use the API

| Method | Endpoint | Result |
| --- | --- | --- |
| POST | `/api/v1/reports` | Accept a job and return `202` with its `reportId` and status URL. |
| GET | `/api/v1/reports/{reportId}` | Return status, timestamps and available result metadata. |
| GET | `/api/v1/reports/{reportId}/download` | Return a signed URL after completion; otherwise `409`. |

Submit a report for the sample data:

```sh
curl -i http://localhost:8081/api/v1/reports \
  -H 'Content-Type: application/json' \
  -d '{"startDate":"2026-10-01","endDate":"2026-10-02"}'
```

Dates use UTC, with an inclusive start and exclusive end. This request selects October 1 and returns five rows from a fresh sample database. Requests allow up to 731 days.

Use the returned `reportId`:

```sh
REPORT_ID=REPLACE_WITH_REPORT_ID
curl -s "http://localhost:8081/api/v1/reports/$REPORT_ID"
```

Poll until the status is `COMPLETED`, then request a download link:

```sh
curl -s "http://localhost:8081/api/v1/reports/$REPORT_ID/download"
```

Open the returned `downloadUrl` to download the CSV. The link expires after five minutes. A `FAILED` job includes a failure description.

## Deployment roles and configuration

The same JAR runs in three profiles:

| Profile | Runs | Usage |
| --- | --- | --- |
| `api` (default) | HTTP API and pending-job dispatcher. | Two request-serving instances. |
| `worker` | One consumer and one MySQL reader; no HTTP server. | One report-generation instance. |
| `local` | API, dispatcher and worker together. | Local development. |

Build once, then run each command in a separate terminal:

```sh
./mvnw clean package
```

```sh
java -jar target/reporting-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=api --server.port=8081
```

```sh
java -jar target/reporting-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=api --server.port=8082
```

```sh
java -jar target/reporting-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```

Stop the combined `local` process before using this layout. Choose one profile per process and deploy one worker copy; each additional worker adds another database reader. No distributed query limit is implemented.

Connection settings and limits are in [application.yaml](src/main/resources/application.yaml). Role profiles control dispatcher/worker activation. Common environment overrides are `MONGODB_URI`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`, `REPORT_MYSQL_URL`, `REPORT_MYSQL_USERNAME`, `REPORT_MYSQL_PASSWORD`, and `REPORT_S3_*`. Use `PORT` to change the API port. For an API on a separate host, set `SERVER_ADDRESS=0.0.0.0` and configure shared dependency endpoints and a storage URL reachable by download clients. With AWS S3, clear `reporting.storage.endpoint` and configure the bucket, region, credentials and addressing style.

## Tests and shutdown

Unit/MVC tests run without external dependencies:

```sh
./mvnw clean test
```

Integration tests require the Compose dependencies and the dedicated sample MySQL database. They temporarily modify source data, so use a local test environment:

```sh
./mvnw -Pintegration clean verify
```

Stop the application with Ctrl+C, then stop dependencies while retaining their data:

```sh
docker compose stop
colima stop
```

## Current boundaries

The service assumes source rows stay unchanged during an export. A worker restart begins a new attempt from the start. Authentication, report ownership, automatic file retention, delayed worker retries and dead-letter handling are not implemented. Query batching limits concurrent work; it does not enforce the database's available 20%/60% capacity. Performance at the full 200-million-row scale remains to be validated on representative hardware and foreground traffic.
