# BiteSync Development & Phase Execution Checklist

This document tracks the phase-by-phase execution of all microservice patterns, distributed systems concepts, security protocols, database strategies, and engineering practices implemented and planned for the BiteSync enterprise backend.

**Current Milestone:** Phases 1, 2 & 3 (Sprints 3A & 3B) Complete (`147` Unit/Web Tests Passing | `130/130` Autonomous Master E2E Assertions Passing across `71` Requests)

---

## Phase 1: Security, Edge & Foundations (Completed)
*(Focus: Securing the perimeter, dynamic routing, setting the baseline)*

- [x] **Monorepo Architecture:** Maven parent-child POM structure (`bitesync-parent`) with centralized `<dependencyManagement>`, `<pluginManagement>`, and clean per-module constants (`BiteSyncCommonConstants`, `GatewayConstants`, `AuthConstants`, `RestaurantConstants`, `OrderConstants`).
- [x] **Service Registry & Discovery:** Spring Cloud Netflix Eureka Server (`discovery-server`, Port `8761`) with loopback `127.0.0.1` pinning for local reliability.
- [x] **Edge API Gateway:** Spring Cloud Gateway (Reactive/Netty, Port `8080`) acting as the single point of entry.
- [x] **Client-Side Load Balancing:** Spring Cloud LoadBalancer dynamically routing via `lb://user-service`, `lb://restaurant-service`, and `lb://order-service`.
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

## Phase 3: The Event-Driven Core (Completed — Sprint 3A & Sprint 3B Done)
*(Focus: Polyglot persistence, high-throughput caching, decoupling microservices & distributed consistency)*

### Sprint 3A: `restaurant-service` (Port `8082`) — Completed
- [x] **NoSQL Database Integration (MongoDB):** Polyglot persistence in `bitesync_menus` using embedded menu documents (`Restaurant -> MenuCategory -> MenuItem`), `DECIMAL128` money precision, and `@EnableMongoAuditing`.
- [x] **ESR Compound Indexing & Composite Cursor Pagination:** Compound indexes (`idx_city_status_rating_id`, `idx_status_rating_id`, `idx_owner_status`) with composite `(afterRating, afterId)` keyset pagination and the `limit + 1` zero-count trick.
- [x] **RBAC Restaurant Onboarding & Ownership Isolation:** JWT `sub`-bound `/apply` (`PENDING_APPROVAL`), Admin-only `/approve` (`ACTIVE`), and `RESTAURANT_OWNER` + ownership-verified `/menu` updates.
- [x] **Multi-Tier Hybrid Caching *(pulled forward from Phase 4)*:** L1 Caffeine (JVM RAM, single-flight locking, differentiated TTLs) + L2 Redis (shared JSON cache with 30m + 0–180s TTL jitter) + Redis Pub/Sub cross-pod L1 eviction + 30s negative caching + graceful MongoDB fallback.

### Sprint 3B: `order-service` (Port `8083`) — Completed
- [x] **Event-Driven Architecture (Apache Kafka — KRaft Mode):** Provisioned 3-partition topics (`bitesync.order.events`, `bitesync.restaurant.events`), `acks=all` + idempotent producers, `enable-auto-commit=false` + `ack-mode=record`, per-`orderId` partition-key FIFO ordering, and live Kafbat Kafka UI (`:8090`).
- [x] **API Idempotency (`Idempotency-Key` + SHA-256):** Atomic `idempotency_records` table with canonical SHA-256 request hashing, safe replay (`X-Idempotent-Replay: true`), and payload-tamper conflict rejection (`ORDER_003`).
- [x] **Transactional Outbox Pattern (`FOR UPDATE SKIP LOCKED`):** Single PostgreSQL ACID commit writing `orders`, `order_items`, `idempotency_records`, and `outbox_events` with multi-pod safe `SKIP LOCKED` relay polling and 5-retry poison-pill protection (`FAILED`).
- [x] **Saga Pattern (Choreography + Authoritative Server-Side Pricing):** Zero-trust checkout where `restaurant-service` bypasses L1/L2 cache to read real MongoDB `DECIMAL128` prices & item availability, emits `RESTAURANT_ORDER_ACCEPTED`/`REJECTED`, and `order-service` transitions `PENDING -> CONFIRMED` or runs compensating rollback `PENDING -> CANCELLED`, backed by atomic `processed_events` consumer idempotency in both MongoDB and PostgreSQL.
- [x] **CQRS (Command Query Responsibility Segregation):** Dedicated Kafka consumer group (`order-query-projector`) projecting lifecycle events into denormalized `order_summary_view` (with out-of-order & replay safety), served by read-only `OrderQueryService` (`GET /api/orders`, `GET /api/orders/{id}`) with zero JOINs and strict ownership + `ADMIN` override.

## Phase 4: Real-Time Communication & Resiliency (Next Up)
*(Focus: Synchronous internal communication, external integrations, fault tolerance)*

- [ ] **Synchronous RPC:** High-speed, type-safe inter-service communication using OpenFeign and/or gRPC (including automated `RESTAURANT_OWNER` role sync).
- [ ] **API Rate Limiting / Throttling:** Redis-backed Token Bucket rate limiting at the API Gateway.
- [ ] **Fault Tolerance (Resilience4j):** Implementing Circuit Breakers, Bulkheads, and Time-limiters to prevent cascading failures.
- [x] **Multi-Tier Caching:** Completed ahead of schedule in Sprint 3A (`restaurant-service`: Caffeine L1 + Redis L2 + Redis Pub/Sub).
- [ ] **Real-Time Communication & Geospatial:** WebSockets for duplex push notifications (live delivery tracking) + Geospatial rider matching (`Redis GEO` / MongoDB `2dsphere`).
- [ ] **External Integrations:** Secure Webhooks (HMAC-SHA256 verified) for third-party payment or delivery updates.
- [ ] **Distributed Locking:** ShedLock to ensure scheduled cron jobs execute on exactly one node.
- [ ] **Virtual Threads:** Leveraging Java 21 Virtual Threads (Project Loom) for high-throughput concurrency.

## Phase 5: Observability, Testing & CI/CD (In Progress)
*(Focus: Production readiness, debugging distributed systems, deployment automation)*

- [x] **API Documentation:** OpenAPI 3.0.3 specifications maintained for `user-service` (`user-auth-api.yaml`), `restaurant-service` (`restaurant-api.yaml`), and `order-service` (`order-api.yaml`).
- [x] **Unit Testing (Phases 1–3):** Business logic, cache orchestrator, outbox relay, choreography saga, CQRS projector, repository query, and reactive filter isolation using JUnit 5, Mockito & Reactor `StepVerifier` across all active modules. *(Ongoing for new services)*
- [x] **Web Layer Testing (Phases 1–3):** `@WebMvcTest` covering controllers, JSON serialization, `@Valid` errors, idempotency headers, and RBAC security filters across `user-service`, `restaurant-service`, and `order-service`. *(Ongoing for new services)*
- [ ] **Integration Testing:** Bootstrapping real Docker DBs & Kafka during Maven builds using Testcontainers.
- [ ] **Contract Testing:** Ensuring backward compatibility between microservices (e.g., Spring Cloud Contract).
- [ ] **Distributed Tracing:** Micrometer & OpenTelemetry injecting W3C/`X-B3-TraceId` automatically across HTTP and Kafka boundaries.
- [ ] **Metrics & Dashboards:** Prometheus scraping time-series data and visualizing it via Grafana.
- [ ] **CI/CD:** GitHub Actions build, test, and deployment automation pipelines.
