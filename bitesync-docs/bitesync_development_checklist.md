# BiteSync Master Architecture & Concepts Checklist

This document is the comprehensive blueprint of all microservice patterns, distributed systems concepts, security protocols, database strategies, and development practices implemented and planned for the BiteSync enterprise backend.

## Phase 1: Security, Edge & Foundations
*(Focus: Securing the perimeter, dynamic routing, setting the baseline)*

- [x] **Monorepo Architecture:** Maven parent-child POM structure for centralized dependency management.
- [x] **Service Registry & Discovery:** Spring Cloud Netflix Eureka Server for dynamic microservice IP resolution.
- [x] **Edge API Gateway:** Spring Cloud Gateway (Reactive/Netty) acting as the single point of entry.
- [x] **Client-Side Load Balancing:** Spring Cloud LoadBalancer dynamically routing via `lb://` protocols.
- [x] **Securing the Perimeter:** OAuth2 Resource Server configured on the Gateway to intercept and mathematically validate JWTs before routing.
- [x] **Distributed Error Handling:** Unified `@RestControllerAdvice` mapping exceptions to standard formats.
- [x] **Universal API Envelope:** Generic `ApiResponse<T>` and `ErrorResponse` payload contracts across all services.

## Phase 2: Core Domain, Databases & Auth Completion
*(Focus: Data persistence, strict schema governance, finalizing token lifecycle)*

- [x] **Database Integration:** PostgreSQL (Relational) with Spring Data JPA.
- [x] **Strict Schema Governance:** Deterministic, version-controlled database migrations using Flyway.
- [x] **Advanced Auth (Stateless JWTs):** Moving away from sessions to purely stateless Bearer tokens.
- [x] **Advanced Auth (Asymmetric Encryption):** RS256 Key Pairs. Private Key signs JWTs (User Service), Public Key verifies them.
- [x] **Advanced Auth (JWK Endpoint):** Exposing public keys (`/certs`) for the Gateway to pull dynamically.
- [x] **Finalizing Token Lifecycle (Refresh Tokens):** Stateful, long-lived UUID tokens stored in PostgreSQL for secure access token renewal.
- [x] **Finalizing Token Lifecycle (Stateless Logout):** Distributed Token Blacklisting using a Redis-backed O(1) lookup.
- [x] **Input Sanitization & Validation:** `@Valid`, `@Email`, and standardized DTO constraints triggering handled `MethodArgumentNotValidException` errors.

## Phase 3: The Event-Driven Core (Pending)
*(Focus: Decoupling microservices and managing distributed data consistency)*

- [ ] **Event-Driven Architecture:** Asynchronous messaging using Apache Kafka (KRaft mode).
- [ ] **CQRS (Command Query Responsibility Segregation):** Separating read and write models for performance scaling.
- [ ] **NoSQL Database Integration:** MongoDB for read-heavy, nested document structures (Restaurant Menus).
- [ ] **Transactional Outbox Pattern:** Preventing "Dual-Write" failures between saving to Postgres and publishing to Kafka.
- [ ] **Saga Pattern (Choreography):** Managing distributed transactions across services without relying on 2-Phase Commit (2PC).

## Phase 4: Real-Time Communication & Resiliency (Pending)
*(Focus: Synchronous internal communication, external integrations, fault tolerance)*

- [ ] **Synchronous RPC:** High-speed, type-safe inter-service communication using OpenFeign and/or gRPC.
- [ ] **Fault Tolerance (Resilience4j):** Implementing Circuit Breakers, Bulkheads, and Time-limiters to prevent cascading failures.
- [ ] **Multi-Tier Caching:** Caching hot data using Caffeine (L1 In-Memory) and Redis (L2 Distributed).
- [ ] **Real-Time Communication:** WebSockets for duplex push notifications (e.g., live delivery tracking).
- [ ] **External Integrations:** Secure Webhooks for third-party payment or delivery updates.
- [ ] **Distributed Locking:** ShedLock to ensure scheduled cron jobs execute on exactly one node.
- [ ] **Virtual Threads:** Leveraging Java 21 Virtual Threads (Project Loom) for high-throughput concurrency.

## Phase 5: Observability, Testing & CI/CD
*(Focus: Production readiness, debugging distributed systems, deployment automation)*

- [x] **API Documentation:** OpenAPI 3.0 (Swagger) specifications manually defined.
- [ ] **Unit Testing:** Business logic isolation using JUnit 5 and Mockito. *(In Progress)*
- [ ] **Web Layer Testing:** Validating controllers and JSON serialization via `@WebMvcTest`. *(In Progress)*
- [ ] **Integration Testing:** Bootstrapping real Docker DBs during Maven builds using Testcontainers.
- [ ] **Contract Testing:** Ensuring backward compatibility between microservices (e.g., Spring Cloud Contract).
- [ ] **API Idempotency:** Utilizing Idempotency Keys to prevent duplicate mutating requests (e.g., double-charging).
- [ ] **Distributed Tracing:** Micrometer & OpenTelemetry injecting `X-B3-TraceId` across HTTP and Kafka boundaries.
- [ ] **Metrics & Dashboards:** Prometheus scraping time-series data and visualizing it via Grafana.
- [ ] **CI/CD:** Deployment automation pipelines.