# Durable job submission: first milestone LLD

This document records milestone 1. Milestone 3 adds generation and a download endpoint; see [the current generation guide](report-generation.md).

## Scope and assumptions

Milestone 1 implements the acceptance boundary of the [HLD](high-level-design.md): a request is validated and durably recorded before returning `202`. It also implements retrieval of the saved job. [Milestone 2](job-dispatch.md) adds dispatch to RabbitMQ; every job remains queued until a worker is introduced.

- A report is a CSV export selected by a date range. The client cannot submit arbitrary columns, filters, SQL, or output formats.
- `startDate` is inclusive; `endDate` is exclusive. Dates are converted to UTC instants before persistence, independently of the machine's timezone.
- The configurable default maximum is **731 days**, an implementation assumption covering two-year ranges that include a leap day. It is a range bound, not a completion-time guarantee.
- Local APIs bind to loopback. Authentication, job ownership, and authorization are required before shared deployment and download access.
- Historical rows are provisionally assumed immutable for the future worker's checkpoint design. The worker milestone must revisit this if a consistent snapshot across changing rows is required.
- For the first worker, a proposed sample source table is `report_events(id, occurred_at, reference, amount, status)`, ordered by `(occurred_at, id)`, with UTC timestamps. Columns, DDL, indexes, and query plans will be finalized and tested with the reader; this milestone does not create or access MySQL.

## API contract

| Operation | Success | Errors |
| --- | --- | --- |
| `POST /api/v1/reports` | `202` with job metadata, relative `statusUrl`, and matching `Location` header. | `400` for missing/invalid dates, an empty or reversed range, an excessive range, or unknown fields; `503` if persistence cannot be confirmed. |
| `GET /api/v1/reports/{reportId}` | `200` with the saved job metadata. | `400` for a malformed UUID; `404` for an unknown UUID; `503` if metadata cannot be read. |

Responses use `Cache-Control: no-store`. Application errors use HTTP Problem Details (`application/problem+json`). Field validation errors include an `errors` map. Internal database details are logged on the server and excluded from error responses.

At milestone 1 there was no download route. Repeating POST creates a new job; request idempotency is a later addition.

## Components

| Component | Responsibility |
| --- | --- |
| `ReportController` | Bind/validate HTTP input, return metadata, and set response status and headers. |
| `ReportService` | Enforce the date-range contract, generate a UUID, normalize UTC boundaries, and insert the queued job. |
| `ReportJobRepository` | Access MongoDB's `report_jobs` collection. Submission uses `insert`, avoiding accidental overwrite/upsert. |
| `ReportJob` | Persist authoritative request parameters, lifecycle metadata, and pending dispatch in one document. |
| `ReportResponse` | Expose API metadata without exposing internal dispatch state. |
| `ReportExceptionHandler` | Map validation, missing jobs, and storage failures to safe HTTP errors. |

The application supplies an injectable UTC `Clock` and explicit acknowledged/journaled MongoDB write settings. Timestamps are normalized to milliseconds to match BSON date precision.

## Stored job

```text
_id              UUID string
startInclusive   UTC BSON date
endExclusive     UTC BSON date
format           CSV
status           QUEUED
submittedAt      UTC BSON date
firstStartedAt   null / absent until processing starts
completedAt      null / absent until terminal completion
attemptCount     0
dispatchPending  true
dispatch         initial dispatch metadata, with attemptCount=0
```

The API derives `retryCount = max(0, attemptCount - 1)`. Neither polling nor future scheduling yields count as execution attempts. The retry implementation will define precisely when attempts begin.

The lifecycle vocabulary is `QUEUED`, `RUNNING`, `RETRYING`, `COMPLETED`, and `FAILED`. Only initial `QUEUED` creation is implemented. Milestone 2 adds dispatch leases and claim tokens, separate from future worker leases, attempt tokens, checkpoints, output references, and processing transitions.

## Submission and durability

1. Validate the JSON request and the date range.
2. Create one document containing the job and `dispatchPending=true`.
3. Insert using `w=majority`, `j=true`, and a five-second write-concern timeout.
4. Return `202` only after successful acknowledgement.

MongoDB guarantees atomicity within a [single document](https://www.mongodb.com/docs/manual/core/write-operations-atomicity/), so a successful insert cannot omit its pending-dispatch marker. The application does not need a cross-system transaction with RabbitMQ at this stage. MongoDB's [write concern](https://www.mongodb.com/docs/manual/reference/write-concern/) defines acknowledgement and journaling behavior. A standalone local MongoDB provides persistence on one machine; it does not provide replica failover.

An unconfirmed write produces `503`, with no accepted response or Location header. A timeout or interrupted response can occur after MongoDB has persisted a document. The client therefore cannot infer that no job exists, and retrying POST can create another job. This ambiguity must be addressed with request idempotency before relying on automatic client retries.

The default driver configuration bounds server selection, connection establishment, and socket waits. MongoDB availability is checked when an API operation accesses metadata; the application can start while MongoDB is unavailable and respond with `503`.

## Verification boundary

Unit/MVC tests cover validation, configured range limits, UTC conversion, initial metadata, HTTP response semantics, and unconfirmed writes. Integration tests run the actual application against MongoDB, inspect its document, restart the application, and fetch the same job. They also exercise the actual HTTP error path against an unreachable MongoDB address.

This establishes durable acceptance and retrieval. It makes no claim about report generation, queue throughput, database capacity, export memory consumption, or completion latency. Those require the dispatcher, worker, and representative workload measurements.
