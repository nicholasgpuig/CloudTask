# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Infrastructure

```bash
# Start all infrastructure (PostgreSQL, Redis, RabbitMQ, Prometheus, Grafana)
cd deploy/compose && docker compose up -d

# Stop infrastructure
cd deploy/compose && docker compose down
```

### Job Service (Java/Spring Boot)

```bash
cd services/job-service-java

# Run the service
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=JobControllerIntegrationTest

# Build without tests
./mvnw package -DskipTests
```

### Go Services

```bash
# Run a Go service
go run main.go

# Build a Go service
go build -o <service-name> .

# Run Go tests
go test ./...
```

## Architecture

CloudTask is a distributed job processing system. Services communicate asynchronously via RabbitMQ.

**Services:**
- `services/job-service-java` — Spring Boot REST API (port 8080). The only user-facing service. Handles authentication, job creation, and job queries. Publishes to RabbitMQ on job creation.
- `services/worker-go` — Consumes `jobs.created`, executes job payloads (currently only `sleep` type), publishes `jobs.started` and `jobs.completed` status updates.
- `services/results-processor-go` — Consumes `jobs.started` and `jobs.completed`, writes status updates directly to PostgreSQL.
- `services/scheduler-go` — Stub; intended for future scheduling/prioritization using Redis distributed locks.

**Message queues:**
- `jobs.created` — job-service → worker
- `jobs.started` — worker → results-processor
- `jobs.completed` — worker → results-processor

**Infrastructure:** PostgreSQL (5432), RabbitMQ (5672, UI at 15672), Redis (6379), Prometheus (9090), Grafana (3000).

## Job Service Internals

The job-service package structure under `com.cloudtask.jobservice`:
- `controller/` — `AuthController` (register/login), `JobController` (CRUD)
- `service/` — `AuthService` (registration, login, JWT issuance)
- `security/` — `JwtUtil` (token generation/validation), `JwtAuthFilter` (per-request auth), `SecurityConfig` (permits `/auth/**` and `/actuator/health`, requires auth for all else)
- `model/` — `User` (email + BCrypt hash), `Job` (type, payload as JSON string, status enum, `@ManyToOne` user FK)
- `messaging/` — `JobPublisher` publishes `JobMessage` to `jobs.created`; `RabbitConfig` declares queues/exchange
- `repository/` — Spring Data JPA repositories; `JobRepository` queries are user-scoped (users only see their own jobs — 404 instead of 403 to prevent enumeration)

**JWT:** Subject is the user UUID; `email` is an additional claim. Configured via `jwt.secret` and `jwt.expiration-ms` in `application.properties`.

**Job payload:** The `payload` field on `Job` is stored as a serialized JSON string (not a JSON column). The worker deserializes it per job type.

## Testing

Integration tests use the `test` Spring profile (`application-test.properties`), which swaps PostgreSQL for H2 in-memory and excludes RabbitMQ auto-configuration. `JobPublisher` is mocked with `@MockitoBean`.

Tests run with the full Spring security filter chain applied (`springSecurity()` in MockMvc setup).
