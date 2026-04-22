# CloudTask Architecture

## Overview

CloudTask is a distributed job processing system designed for scalability and reliability.

## Components

### Job Service (Java/Spring Boot)
- REST API for job CRUD operations
- JWT-based authentication system for user management
- User registration and login endpoints
- Publishes `JobCreated` events to RabbitMQ
- Stores job metadata and user information in PostgreSQL
- Enforces user-scoped job access (users can only see their own jobs)

### Worker (Go)
- Consumes jobs from `jobs.created` queue
- Executes job payloads
- Publishes results to `jobs.completed` queue

### Scheduler (Go)
- Manages job scheduling and prioritization
- Uses Redis for distributed locking
- Handles delayed/scheduled jobs

### Results Processor (Go)
- Consumes from `jobs.completed` queue
- Updates job status in PostgreSQL
- Stores execution results and metrics

## Data Flow

### Authentication Flow
```
Client -> POST /auth/register or /auth/login -> Job Service
                                                      |
                                                      v
                                                  Validate credentials
                                                      |
                                                      v
                                                Generate JWT token
                                                      |
                                                      v
                                                Return token to Client
```

### Job Processing Flow
```
Client (with JWT) -> Job Service -> [jobs.created queue] -> Worker
                          |                                     |
                          v                                     v
                    Link job to user                    Execute job
                          |                                     |
                          v                                     v
                      PostgreSQL                     [jobs.completed queue]
                                                               |
                                                               v
                                                     Results Processor
                                                               |
                                                               v
                                                          PostgreSQL
                                                     (update job status)
```

## Security

### Authentication & Authorization

- **JWT-based Authentication**: Users authenticate via email/password to receive JWT tokens
- **Password Security**: Passwords are hashed using BCrypt before storage in PostgreSQL
- **Token-based Authorization**: All job operations require a valid JWT Bearer token
- **User Scoping**: Jobs are linked to the user who created them
- **Access Control**: Users can only view and access their own jobs
- **Token Expiration**: JWT tokens expire after a configured period for security

### API Security

- Public endpoints: `/auth/register`, `/auth/login`
- Protected endpoints: All `/jobs/*` endpoints require JWT authentication
- Authorization header format: `Authorization: Bearer <token>`

## Infrastructure

- **PostgreSQL**: Primary data store (jobs, users, and authentication data)
- **RabbitMQ**: Message broker for async communication
- **Redis**: Caching and distributed locks
- **Prometheus**: Metrics collection (port 9090)
- **Loki**: Log aggregation (port 3100)
- **Promtail**: Log shipper — auto-discovers Docker container logs and forwards to Loki
- **Grafana**: Dashboards for metrics (Prometheus) and logs (Loki) (port 3000)

## Observability

### Metrics

Metrics flow from services → Prometheus → Grafana. Prometheus scrapes each service every 15 seconds.

| Service | Endpoint | Port |
|---------|----------|------|
| job-service | `/actuator/prometheus` | 8080 |
| worker | `/metrics` | 9091 |
| results-processor | `/metrics` | 9092 |

**job-service** uses Micrometer with the Prometheus registry. Custom metrics are defined in `JobMetrics.java`:
- `jobs_created_total` (Counter) — tagged by job `type`
- `jobs_created_seconds` (Timer, histogram) — job creation latency, tagged by `type`

Spring Boot's HTTP server request metrics are also auto-collected with percentile histograms enabled (`management.metrics.distribution.percentiles-histogram.http.server.requests=true`).

**worker-go** and **results-processor-go** use the Prometheus Go client library directly:

*worker:*
- `worker_jobs_processed_total` (CounterVec) — labeled by `type` and `status` (COMPLETED/FAILED)
- `worker_job_duration_seconds` (HistogramVec) — labeled by `type`

*results-processor:*
- `processor_status_updates_total` (CounterVec) — labeled by `status` (RUNNING/COMPLETED/FAILED)
- `processor_db_update_duration_seconds` (Histogram) — database write latency
- `processor_nack_total` (Counter) — messages that failed to persist

### Logs

Logs flow from services → Promtail → Loki → Grafana. Promtail uses Docker service discovery and automatically collects logs from all containers without any per-service configuration.

**job-service** uses Logback with ECS (Elastic Common Schema) JSON format in non-test profiles (`logback-spring.xml`). This produces structured, machine-parseable logs compatible with log aggregation tooling.

**worker-go** and **results-processor-go** use Go's `slog` package with `NewJSONHandler` writing structured JSON to stdout. Key log fields: `jobId`, `type`, `status`, `duration_ms`, `error`.

All services write logs to stdout only; Promtail handles collection via the Docker socket.

### Grafana Dashboards

Dashboards and data sources are fully provisioned from files under `deploy/compose/grafana/provisioning/` — no manual Grafana setup is required. Two dashboards are included:

- **API Health** — HTTP request rates and latencies for the job-service
- **Job Processing** — job throughput, duration, and status breakdown across the worker and results-processor

Data sources (Prometheus and Loki) are provisioned as non-editable to prevent configuration drift.
