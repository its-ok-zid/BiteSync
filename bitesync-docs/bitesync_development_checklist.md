# BiteSync Development & Phase Execution Checklist

This document tracks the phase-by-phase execution of all microservice patterns, distributed systems concepts, security protocols, database strategies, and engineering practices implemented and planned for the BiteSync enterprise backend.

**Current Milestone:** Phases 1, 2 & Sprint 3A Complete (`89` Unit/Web Tests Passing | `79/79` Autonomous Master E2E Assertions Passing)

---

## Phase 1: Security, Edge & Foundations (Completed)
*(Focus: Securing the perimeter, dynamic routing, setting the baseline)*

- [x] **Monorepo Architecture:** Maven parent-child POM structure (`bitesync-parent`) with centralized `<dependencyManagement>` and `<pluginManagement>`.
- [x] **Service Registry & Discovery:** Spring Cloud Netflix Eureka Server (`discovery-server`, Port `8761`) with loopback `127.0.0.1` pinning for local reliability.
- [x] **Edge API Gateway:** Spring Cloud Gateway (Reactive/Netty, Port `8080`) acting as the single point of entry.
- [x] **Client-Side Load Balancing:** Spring Cloud LoadBalancer dynamically routing via `lb://user-service` and `lb://restaurant-service`.
- [x] **Securing the Perimeter:** Reactive OAuth2 Resource Server on the Gateway validating JWT signatures via dynamic JWK URI, permitting only public auth & read-only restaurant browse routes.
- [x] **Distributed Error Handling:** Unified `@RestControllerAdvice` (`GlobalExceptionHandler` in `bitesync-common`) handling `BiteSyncException`, `MethodArgumentNotValidException`, `AccessDeniedException`, and sanitized `500` fallbacks.
- [x] **Universal API Envelope:** Generic `ApiResponse<T>` and `ErrorResponse` contracts with `X-B3-TraceId` correlation across all services.

## Phase 2: Core Domain, Databases & Auth Completion (Completed)
*(Focus: Data persistence, strict schema governance, RBAC, finalizing token lifecycle)*

- [x] **Database Integration:** PostgreSQL 15 (Relational ACID / CP) with Spring Data JPA (`hibernate.ddl-auto: validate`).
- [x] **Strict Schema Governance:** Deterministic, version-controlled Flyway migrations (`V1`–`V4` including `user_roles` mapping table and BCrypt-seeded Super Admin).
- [x] **Advanced Auth (Stateless JWTs & RS256):** Asymmetric 2048-bit RSA key pairs—Private Key signs JWTs in `user-service`, Public Key exposed via `/api/auth/certs` JWK endpoint for `api-gateway` and downstream resource servers.
- [x] **Multi-Role RBAC & Secure Onboarding:** `Set<Role>` (`CUSTOMER`, `RIDER`, `RESTAURANT_OWNER`, `ADMIN`, `SUPPORT`), privilege-escalation prevention (public registration strictly assigns `CUSTOMER`), and `@PreAuthorize("hasRole('ADMIN')")` role-upgrade API.
- [x] **Finalizing Token Lifecycle (Refresh Tokens):** Stateful, 7-day UUID refresh tokens stored in PostgreSQL with single-session enforcement and post-logout revocation.
- [x] **Finalizing Token Lifecycle (Stateless Logout):** Distributed Redis-backed JWT blacklisting (`jwt:blacklist:<token>`) enforced reactively at the API Gateway (`BlacklistFilter`).
- [x] **Input Sanitization & Validation:** `@Valid`, `@Email`, `@NotBlank`, `@Size`, and `@NotNull` DTO constraints across all endpoints.

## Phase 3: The Event-Driven Core (In Progress — Sprint 3A Complete, Sprint 3B Next)
*(Focus: Polyglot persistence, high-throughput caching, decoupling microservices & distributed consistency)*

### Sprint 3A: `restaurant-service` (Port `8082`) — Completed
- [x] **NoSQL Database Integration (MongoDB):** Polyglot persistence in `bitesync_menus` using embedded menu documents (`Restaurant -> MenuCategory -> MenuItem`), `DECIMAL128` money precision, and `@EnableMongoAuditing`.
- [x] **ESR Compound Indexing & Composite Cursor Pagination:** Compound indexes (`idx_city_status_rating_id`, `idx_status_rating_id`, `idx_owner_status`) with composite `(afterRating, afterId)` keyset pagination and the `limit + 1` zero-count trick.
- [x] **RBAC Restaurant Onboarding & Ownership Isolation:** JWT `sub`-bound `/apply` (`PENDING_APPROVAL`), Admin-only `/approve` (`ACTIVE`), and `RESTAURANT_OWNER` + ownership-verified `/menu` updates.
- [x] **Multi-Tier Hybrid Caching *(pulled forward from Phase 4)*:** L1 Caffeine (JVM RAM, single-flight locking, differentiated TTLs) + L2 Redis (shared JSON cache with 30m + 0–180s TTL jitter) + Redis Pub/Sub cross-pod L1 eviction + 30s negative caching + graceful MongoDB fallback.

### Sprint 3B: `order-service` (Port `8083`) — Next Up
- [ ] **Event-Driven Architecture:** Asynchronous messaging using Apache Kafka (KRaft mode).
- [ ] **CQRS (Command Query Responsibility Segregation):** Separating read and write models for performance scaling.
- [ ] **Transactional Outbox Pattern:** Preventing "Dual-Write" failures between saving to PostgreSQL and publishing to Kafka.
- [ ] **Saga Pattern (Choreography):** Managing distributed transactions across services without relying on 2-Phase Commit (2PC).
- [ ] **API Idempotency:** Utilizing Idempotency Keys to prevent duplicate mutating requests (e.g., double-charging).

## Phase 4: Real-Time Communication & Resiliency (Pending)
*(Focus: Synchronous internal communication, external integrations, fault tolerance)*

- [ ] **Synchronous RPC:** High-speed, type-safe inter-service communication using OpenFeign and/or gRPC (including automated `RESTAURANT_OWNER` role sync).
- [ ] **API Rate Limiting / Throttling:** Redis-backed Token Bucket rate limiting at the API Gateway.
- [ ] **Fault Tolerance (Resilience4j):** Implementing Circuit Breakers, Bulkheads, and Time-limiters to prevent cascading failures.
- [x] **Multi-Tier Caching:** Completed ahead of schedule in Sprint 3A (`restaurant-service`: Caffeine L1 + Redis L2 + Redis Pub/Sub).
- [ ] **Real-Time Communication:** WebSockets for duplex push notifications (e.g., live delivery tracking).
- [ ] **External Integrations:** Secure Webhooks for third-party payment or delivery updates.
- [ ] **Distributed Locking:** ShedLock to ensure scheduled cron jobs execute on exactly one node.
- [ ] **Virtual Threads:** Leveraging Java 21 Virtual Threads (Project Loom) for high-throughput concurrency.

## Phase 5: Observability, Testing & CI/CD (In Progress)
*(Focus: Production readiness, debugging distributed systems, deployment automation)*

- [x] **API Documentation:** OpenAPI 3.0.3 specifications maintained for `user-service` (`user-auth-api.yaml`) and `restaurant-service` (`restaurant-api.yaml`).
- [x] **Unit Testing (Phases 1–3A):** Business logic, cache orchestrator, repository query, and reactive filter isolation using JUnit 5, Mockito & Reactor `StepVerifier` across all active modules. *(Ongoing for new services)*
- [x] **Web Layer Testing (Phases 1–3A):** `@WebMvcTest` covering controllers, JSON serialization, `@Valid` errors, and RBAC security filters in `user-service` and `restaurant-service`. *(Ongoing for new services)*
- [ ] **Integration Testing:** Bootstrapping real Docker DBs during Maven builds using Testcontainers.
- [ ] **Contract Testing:** Ensuring backward compatibility between microservices (e.g., Spring Cloud Contract).
- [ ] **Distributed Tracing:** Micrometer & OpenTelemetry injecting W3C/`X-B3-TraceId` automatically across HTTP and Kafka boundaries.
- [ ] **Metrics & Dashboards:** Prometheus scraping time-series data and visualizing it via Grafana.
- [ ] **CI/CD:** GitHub Actions build, test, and deployment automation pipelines.
