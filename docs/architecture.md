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
- **Prometheus**: Metrics collection
- **Grafana**: Monitoring dashboards
