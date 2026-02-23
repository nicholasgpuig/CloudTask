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

## Authentication

The job-service uses JWT-based authentication for secure access to job operations. All users must register or login to receive a JWT token, which is required for creating and managing jobs.

### Registering a User

```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "securePassword123"
  }'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### Logging In

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "securePassword123"
  }'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### Using the JWT Token

Include the JWT token in the `Authorization` header with the `Bearer` scheme for all authenticated endpoints:

```bash
curl -X POST http://localhost:8080/jobs \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "payload": {"to": "recipient@example.com", "subject": "Hello"}
  }'
```

### Security Notes

- Passwords are hashed using BCrypt before storage
- JWT tokens expire after a configured period
- Jobs are scoped to users - each user can only access their own jobs
- Tokens must be kept secure and should not be shared

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

## API Endpoints

### Authentication (No Auth Required)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/register` | Register new user with email/password - returns JWT token |
| POST | `/auth/login` | Login with email/password - returns JWT token |

### Jobs (Auth Required - Bearer Token)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/jobs` | Create a new job (linked to authenticated user) |
| GET | `/jobs` | List all jobs for the authenticated user |
| GET | `/jobs/{id}` | Get a specific job by ID (only if owned by user) |

**Note:** All job endpoints require authentication via JWT Bearer token. Jobs are user-scoped - users can only access jobs they created.

## Message Queues

| Queue | Publisher | Consumer | Purpose |
|-------|-----------|----------|---------|
| `jobs.created` | job-service | worker | New jobs to process |
| `jobs.completed` | worker | results-processor | Completed job results |
