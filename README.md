# CloudTask

A distributed job processing system with microservices architecture.

## Architecture

- **job-service** (Java/Spring Boot) - REST API for job management, publishes to RabbitMQ
- **worker** (Go) - Consumes jobs from queue and executes them
- **scheduler** (Go) - Schedules jobs based on timing and priority
- **results-processor** (Go) - Consumes job completion events and persists results

## Prerequisites

- Docker & Docker Compose
- Java 25+ (for job-service development)
- Go 1.23+ (for Go services development)

## Quick Start

### 1. Start Infrastructure

```bash
cd deploy/compose
docker compose up -d
```

This starts:
- PostgreSQL (port 5432)
- Redis (port 6379)
- RabbitMQ (port 5672, management UI at 15672)
- Prometheus (port 9090)
- Grafana (port 3000)

### 2. Run Job Service

```bash
cd services/job-service-java
./mvnw spring-boot:run
```

### 3. Verify

```bash
# Health check
curl http://localhost:8080/actuator/health

# RabbitMQ Management UI
open http://localhost:15672  # guest/guest

# Grafana
open http://localhost:3000   # admin/admin
```

## Project Structure

```
cloudtask/
├── services/
│   ├── job-service-java/     # Job API (Spring Boot)
│   ├── worker-go/            # Job worker (Go)
│   ├── scheduler-go/         # Job scheduler (Go)
│   └── results-processor-go/ # Results handler (Go)
├── libs/
│   └── shared/               # Shared schemas
├── deploy/
│   └── compose/              # Docker Compose files
├── scripts/
│   └── loadtest/             # k6 load tests
└── docs/
    └── architecture.md       # Architecture docs
```

## Message Queues

| Queue | Publisher | Consumer | Purpose |
|-------|-----------|----------|---------|
| `jobs.created` | job-service | worker | New jobs to process |
| `jobs.completed` | worker | results-processor | Completed job results |
