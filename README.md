# BiteSync: Advanced Spring Boot Microservices Architecture

BiteSync is a production-grade food delivery backend built to demonstrate enterprise microservice patterns, distributed data management, and cloud-native resiliency. 

## 🏗 Architecture Overview

This project is structured as a Maven Multi-Module Monorepo. It implements strict CQRS, Event-Driven choreographies via Apache Kafka, and the Saga pattern for distributed transactions.

![Uploading mermaid-diagram.png…]()


## 🚀 Tech Stack & Core Patterns

### Infrastructure & Routing
* **Java 21** & **Spring Boot 3.2+**
* **Spring Cloud Gateway**: Edge routing, rate limiting, and API composition.
* **Spring Cloud Netflix Eureka**: Dynamic service discovery and registry.

### Security & Authentication
* **Spring Security OAuth2**: Delegated authorization.
* **Stateless JWT**: Token-based authentication with RSA key-pair signing.

### Data & Persistence
* **PostgreSQL**: Strict ACID relational data (Order & User Services).
* **MongoDB**: High-read NoSQL document storage (Restaurant Service).
* **Redis & Caffeine**: Multi-tier (L1/L2) caching strategies.
* **Flyway**: Deterministic database schema migration and version control.

### Communication & Distributed Patterns
* **Apache Kafka**: High-throughput event streaming.
* **Transactional Outbox Pattern**: Preventing dual-write failures between Postgres and Kafka.
* **Saga Pattern**: Coordinating distributed transactions across independent boundaries.
* **gRPC / OpenFeign**: Synchronous high-speed inter-service communication.
* **WebSockets**: Real-time duplex push notifications for delivery tracking.

### Resiliency & Observability
* **Resilience4j**: Circuit breakers, bulkheads, and time-limiters to prevent cascading failures.
* **ShedLock**: Distributed locking for concurrent cron-job schedulers.
* **Micrometer & OpenTelemetry**: W3C Trace Context propagation across microservices.
* **Prometheus & Grafana**: Time-series metrics and dashboarding.

## 🛠 Local Development Setup

### Prerequisites
* Docker & Docker Compose
* Java 21 JDK
* Maven 3.9+

### Bootstrapping Infrastructure
The required databases, brokers, and telemetry tools are containerized. 
```bash
docker-compose -f docker-compose.infra.yml up -d
