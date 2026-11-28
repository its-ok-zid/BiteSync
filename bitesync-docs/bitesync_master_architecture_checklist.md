# BiteSync Master Architecture & Concepts Checklist

This document tracks every enterprise concept, pattern, and tool discussed for the BiteSync Microservices backend.

**Legend:**
- ✅ **Completed:** Code is written, unit/E2E tested, and integrated.
- 🔄 **Ongoing:** Implemented and passing for all built modules; continues with each new service.
- ⏳ **Pending:** Planned for an upcoming sprint/phase.

---

## 1. Architectural Foundations & Infrastructure

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Monorepo / Multi-Module Setup** | Maven parent-child POM (`bitesync-parent`) managing 6 child modules cleanly with zero build warnings and centralized constants. |
| ✅ | **Service Registry & Discovery** | Spring Cloud Netflix Eureka Server (`discovery-server:8761`) with loopback IP pinning (`127.0.0.1`). |
| ✅ | **Edge API Gateway** | Spring Cloud Gateway (Reactive/Netty, Port `8080`) routing `/api/auth/**`, `/api/users/**`, `/api/restaurants/**`, `/api/orders/**`. |
| ✅ | **Client-Side Load Balancing** | Spring Cloud LoadBalancer integrated into Gateway (`lb://user-service`, `lb://restaurant-service`, `lb://order-service`). |
| ✅ | **Containerization** | Docker Compose (`docker-compose.infra.yml`) for PostgreSQL, MongoDB, Redis, Redis Insight, Kafka (KRaft), and Kafbat Kafka UI (`:8090`). |

## 2. Security & Identity Management

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Stateless Authentication** | Purely stateless Bearer JWT authentication across Gateway, `user-service`, `restaurant-service`, and `order-service`. |
| ✅ | **Asymmetric Encryption (RS256)** | 2048-bit RSA Key Pair; Private Key signs in `user-service`, Public Key shared via `/api/auth/certs` JWK endpoint. |
| ✅ | **OAuth2 Resource Server & RBAC** | Multi-role `Set<Role>` (`roles` claim -> `ROLE_*`), `@PreAuthorize` method security, JWT `sub` ownership guards, and `ADMIN` read overrides. |
| ✅ | **Refresh Token Lifecycle** | 7-day UUID tokens in PostgreSQL with single-session rotation and full DB revocation on logout. |
| ✅ | **Distributed Token Blacklisting** | Redis-backed O(1) lookup (`jwt:blacklist:<token>`) enforced reactively in Gateway `BlacklistFilter`. |
| ✅ | **Input Sanitization & Validation** | `@Valid`, `@Email`, `@NotBlank`, `@DecimalMin`, `ObjectId.isValid()`, UUID parsing, and duplicate `itemId` validation. |

## 3. Distributed Data & Communication

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Event-Driven Architecture** | Apache Kafka in KRaft mode (`bitesync.order.events`, `bitesync.restaurant.events`, 3 partitions, `orderId` partition-key FIFO, `acks=all`, idempotent producers, manual record ack). |
| ✅ | **CQRS** | Separated `OrderCommandService` (normalized `orders`/`order_items`) from `OrderQueryService` (denormalized `order_summary_view` projected asynchronously by `OrderReadModelProjector`). |
| ✅ | **Transactional Outbox Pattern** | Atomic DB commit (`orders` + `outbox_events` + `idempotency_records`) + `FOR UPDATE SKIP LOCKED` relay scheduler with 5-retry poison-pill protection. |
| ✅ | **Saga Pattern (Choreography)** | Cross-service async validation (`order-service` <-> `restaurant-service`) with authoritative MongoDB pricing, compensating `CANCELLED` rollbacks, and `processed_events` idempotency in both DBs. |
| ⏳ | **Synchronous RPC** | Inter-service blocking calls using OpenFeign and/or gRPC — *Phase 4*. |
| ⏳ | **Real-Time Push Notifications** | WebSockets for live delivery tracking to the client (+ Geospatial rider matching) — *Phase 4*. |

## 4. Database, ORM & Consistency (CAP Theorem)

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Relational ACID Data (CP)** | PostgreSQL 15 + Spring Data JPA (`ddl-auto: validate`) with isolated DB-per-Service (`bitesync_users` & `bitesync_orders`). |
| ✅ | **NoSQL Document Data (Polyglot)** | MongoDB 6 (`bitesync_menus`) for nested Restaurant & Menu documents with `DECIMAL128` prices and `processed_events` idempotency. |
| ✅ | **Schema Versioning** | Deterministic Flyway SQL migrations (`V1`–`V4` in `user-service`, `V1`–`V4` in `order-service`). |
| ✅ | **Automated Auditing** | JPA `@PrePersist`/`@PreUpdate` (`TIMESTAMPTZ`) in PostgreSQL and `@EnableMongoAuditing` (`@CreatedDate`/`@LastModifiedDate`) in MongoDB. |
| ✅ | **Database Indexing (B-Tree, Partial & ESR)** | B-Tree indexes, PostgreSQL partial index on `outbox_events (WHERE status = 'PENDING')`, and MongoDB ESR compound indexes. |
| ✅ | **Pagination & Sorting** | Composite Keyset/Cursor pagination `(afterRating, afterId)` with `limit + 1` zero-count detection. |
| ✅ | **CAP Theorem Trade-offs** | CP PostgreSQL ACID writes (`user`/`order`), CP MongoDB checkout reads, and tunable AP eventual-consistency cached browse reads + CQRS read views. |

## 5. Performance & Caching

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Local In-Memory Caching (L1)** | Caffeine cache (max 500 entries, 2m hit TTL / 30s negative TTL, single-flight locking, live stats). |
| ✅ | **Distributed Caching (L2)** | Redis JSON cache (`restaurant:cache:<id>`) with 30m + 0–180s TTL jitter and graceful DB fallback. |
| ✅ | **Multi-Tier Cache & Pub/Sub Eviction** | Cache-Aside + Evict-on-Write + Redis Pub/Sub (`cache:evict:restaurant`) cross-pod L1 invalidation + negative caching. |

## 6. Resiliency, Concurrency & API Protection

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **API Idempotency** | `Idempotency-Key` header + canonical SHA-256 request hashing in `idempotency_records` with `X-Idempotent-Replay: true` and `ORDER_003` conflict guards. |
| ⏳ | **API Rate Limiting / Throttling** | Redis-backed Token Bucket algorithm in the Gateway — *Phase 4*. |
| ⏳ | **Circuit Breaker Pattern** | Resilience4j to fail-fast if a downstream service goes offline — *Phase 4*. |
| ⏳ | **Bulkheads & Time-limiters** | Resilience4j resource isolation and timeout enforcement — *Phase 4*. |
| ⏳ | **Distributed Locking (Scheduler)** | ShedLock to ensure cron jobs execute on exactly one instance — *Phase 4*. |
| ⏳ | **Virtual Threads (Project Loom)** | Java 21 Virtual Threads for high-throughput blocking I/O concurrency — *Phase 4*. |

## 7. Cross-Cutting Concerns & Observability

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **Global Exception Handling** | Shared `@RestControllerAdvice` (`bitesync-common`) unifying errors into `ErrorResponse`. |
| ✅ | **Universal API Envelopes** | Standardized `ApiResponse<T>` wrapper for every success payload. |
| ✅ | **Trace ID Correlation** | Extracting `X-B3-TraceId` header (with UUID fallback) into every HTTP success/error response. |
| ⏳ | **Distributed Tracing (W3C)** | Micrometer & OpenTelemetry automatically propagating Trace IDs across HTTP and Kafka — *Phase 5*. |
| ⏳ | **Metrics & Monitoring** | Prometheus scraping time-series data and visualizing it in Grafana — *Phase 5*. |

## 8. Quality Assurance & Engineering Standards

| Status | Concept / Pattern | Description / Implementation Detail |
| :--- | :--- | :--- |
| ✅ | **API Contracts (OpenAPI 3.0)** | Synchronized `user-auth-api.yaml`, `restaurant-api.yaml`, and `order-api.yaml` specs in `bitesync-docs/`. |
| ✅ | **Automated E2E API Testing** | Single-click Master Postman Collection (`5` folders, `71` requests, `130/130` passing assertions). |
| 🔄 | **Unit Testing (Business Logic)** | `147` passing JUnit 5 / Mockito / Reactor `StepVerifier` tests across `common`, `gateway`, `user`, `restaurant`, and `order` services. |
| 🔄 | **Web Layer Testing** | `@WebMvcTest` suites validating controllers, DTO validation, idempotency headers, JSON contracts, and RBAC security in `user`, `restaurant`, and `order` services. |
| ⏳ | **Integration Testing** | Testcontainers spinning up real Docker DBs/Kafka during Maven builds — *Phase 5*. |
| ⏳ | **Contract Testing & CI/CD** | Spring Cloud Contract & GitHub Actions automated pipelines — *Phase 5*. |
