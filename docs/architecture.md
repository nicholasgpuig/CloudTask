# CloudTask Architecture

## Overview

CloudTask is a distributed job processing system designed for scalability and reliability.

## Components

### Job Service (Java/Spring Boot)
- REST API for job CRUD operations
- Publishes `JobCreated` events to RabbitMQ
- Stores job metadata in PostgreSQL

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

```
Client -> Job Service -> [jobs.created queue] -> Worker
                                                    |
                                                    v
         Results Processor <- [jobs.completed queue]
                |
                v
            PostgreSQL
```

## Infrastructure

- **PostgreSQL**: Primary data store
- **RabbitMQ**: Message broker for async communication
- **Redis**: Caching and distributed locks
- **Prometheus**: Metrics collection
- **Grafana**: Monitoring dashboards
