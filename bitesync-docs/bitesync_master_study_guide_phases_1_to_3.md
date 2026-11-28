# BiteSync Master Engineering & Study Handbook (Phases 1–3)

> **Purpose of This Handbook:** A single, top-to-bottom reference you can read without scrolling through chat history. It maps every distributed-systems concept we implemented across **Phases 1, 2, 3A, and 3B** directly to the exact BiteSync classes, database tables/collections, Redis keys, Kafka topics, curated YouTube videos, and interview blueprints.
>
> **Current Verified Milestone:** `6` Active Modules | `147` Unit & Web-Layer Tests Passing | `130/130` Master E2E Postman Assertions Passing (`71` Requests)

---

## 1. The Big Picture: BiteSync End-to-End Architecture (Phases 1–3)

```text
                           [ Mobile / Web Client / Postman ]
                                           │
                                           ▼
                      ┌─────────────────────────────────────────┐
                      │   api-gateway  (Port 8080 · WebFlux)    │
                      │  1. Validates RS256 JWT via JWK /certs  │
                      │  2. Checks Redis BlacklistFilter (O(1)) │
                      │  3. Load-balances via Eureka (lb://...) │
                      └───────────┬───────────────┬─────────────┘
                                  │               │
                 ┌────────────────┘               └────────────────┐
                 ▼                                                 ▼
   ┌──────────────────────────┐                      ┌──────────────────────────┐
   │ discovery-server (8761)  │                      │  bitesync-common (JAR)   │
   │ Netflix Eureka Registry  │                      │  ApiResponse, ErrorResp  │
   │ (127.0.0.1 IP pinned)    │                      │  GlobalExceptionHandler  │
   └──────────────────────────┘                      │  BiteSyncCommonConstants │
                                                     └──────────────────────────┘
                 │
   ┌─────────────┼───────────────────────────┬──────────────────────────────────┐
   ▼             ▼                           ▼                                  ▼
┌────────────────────────────┐  ┌───────────────────────────────┐  ┌──────────────────────────────┐
│   user-service (8081)      │  │   restaurant-service (8082)   │  │     order-service (8083)     │
│  • Auth, RS256 KeyPair     │  │  • Onboarding & Menu RBAC     │  │  • Command Side (POST)       │
│  • Multi-role Set<Role>    │  │  • ESR Cursor Pagination      │  │  • SHA-256 Idempotency       │
│  • Refresh Tokens & Logout │  │  • L1 Caffeine + L2 Redis     │  │  • Transactional Outbox      │
│                            │  │  • Redis Pub/Sub L1 Eviction  │  │  • Saga Outcome Consumer     │
│  DB: PostgreSQL            │  │  • Kafka Order Validator Saga │  │  • CQRS Projector & Queries  │
│      (bitesync_users)      │  │                               │  │                              │
│      Flyway V1–V4          │  │  DB: MongoDB (bitesync_menus) │  │  DB: PostgreSQL              │
│  Cache: Redis (Blacklist)  │  │  Cache: Caffeine + Redis      │  │      (bitesync_orders)       │
└────────────────────────────┘  └──────────────┬────────────────┘  │      Flyway V1–V4            │
                                               │                   └──────────────┬───────────────┘
                                               │    Apache Kafka (KRaft :9094)    │
                                               │◄── bitesync.order.events ────────┤
                                               ├─── bitesync.restaurant.events ──►│
                                               └──────────────────────────────────┘
```

---

## 2. How to Explain BiteSync End-to-End (Chronological Code Flow)

If a fresher joins your team—or an interviewer asks *"Walk me through what happens from the moment a user opens BiteSync until their Biryani order is confirmed"*—trace these **5 chronological journeys** directly through your codebase:

### Journey A: User Registration, Login & Stateless Logout (`api-gateway` $\rightarrow$ `user-service`)
1. **Registration (`POST /api/auth/register`):**
   - `api-gateway` (`GatewaySecurityConfig`) permits `/api/auth/register` without a JWT and routes to `lb://user-service`.
   - `AuthController.register()` validates `@Valid RegisterRequest`, checks duplicate email (`AUTH_001`), hashes the password with `BCryptPasswordEncoder`, **hardcodes `Set.of(Role.CUSTOMER)`** (prevents privilege escalation), and saves to PostgreSQL (`users` + `user_roles` tables managed by Flyway `V1`–`V4`).
2. **Login (`POST /api/auth/login`):**
   - `AuthController.login()` verifies BCrypt password (`AUTH_002`).
   - `TokenService.generateAccessToken()` signs a 15-minute JWT using the **RSA Private Key** (`NimbusJwtEncoder`, RS256), embedding `sub` (User UUID), `email`, and `roles` (`List<String>`).
   - `TokenService.createRefreshToken()` deletes any old refresh token for that user and saves a 7-day UUID token in PostgreSQL (`refresh_tokens`).
3. **Every Authenticated Request (`api-gateway` $\rightarrow$ Any Service):**
   - `api-gateway` fetches the **RSA Public Key** from `GET http://localhost:8081/api/auth/certs` (JWKS) to cryptographically verify the JWT signature locally with zero DB calls.
   - `BlacklistFilter` (Reactive `WebFilter`) checks Redis key `jwt:blacklist:<token>` in $O(1)$ time. If present, it immediately aborts with `401 Unauthorized`.
   - Downstream services (`restaurant-service`, `order-service`) each run their own `SecurityConfig` (`JwtAuthenticationConverter`) mapping the JWT `roles` claim to `ROLE_*` authorities so `@PreAuthorize` works natively.
4. **Logout (`POST /api/auth/logout`):**
   - `AuthController.logout()` calculates the remaining TTL of the JWT, writes `jwt:blacklist:<token>` into Redis via `TokenBlacklistService` (auto-expires when the JWT expires), and deletes the user's rows in PostgreSQL `refresh_tokens`.

### Journey B: Restaurant Onboarding & ESR Cursor Browse (`restaurant-service`)
1. **Apply (`POST /api/restaurants/apply`):** Any logged-in user applies. `RestaurantController` extracts `ownerId = jwt.getSubject()` (never trusts `ownerId` from JSON), and `RestaurantService.applyForRestaurant()` saves a MongoDB document in `bitesync_menus.restaurants` with `status = PENDING_APPROVAL` and normalized `cityKey` (lowercase trimmed city).
2. **Approve (`PATCH /api/restaurants/{id}/approve`):** Locked behind `@PreAuthorize("hasRole('ADMIN')")`. Flips status to `ACTIVE` and calls `restaurantCacheService.evict(id)` to clear any 30-second negative-cache sentinel.
3. **Update Menu (`PUT /api/restaurants/{id}/menu`):** Locked behind `@PreAuthorize("hasRole('RESTAURANT_OWNER')")`. `RestaurantService.updateMenu()` enforces **both** `restaurant.getOwnerId().equals(jwt.sub)` (prevents cross-owner tampering $\rightarrow$ `403`) and `status == ACTIVE` (`REST_005`). Saves embedded `MenuCategory` $\rightarrow$ `MenuItem` prices using MongoDB `DECIMAL128` (`BigDecimal`) and calls `restaurantCacheService.evict(id)` (**Evict-on-Write**).
4. **Browse (`GET /api/restaurants?city=...&afterRating=...&afterId=...&size=10`):**
   - `RestaurantCustomRepositoryImpl.findByCursor()` runs against the **ESR compound index** `{ cityKey: 1, status: 1, rating: -1, _id: -1 }`.
   - Uses composite keyset condition `(rating < afterRating) OR (rating == afterRating AND _id < afterId)` and fetches `limit + 1` documents to compute `hasNext` without an expensive `COUNT(*)` scan.

### Journey C: Multi-Tier Hybrid Menu Read (`GET /api/restaurants/{id}`)
1. `RestaurantService.getActiveRestaurantById()` validates the 24-hex MongoDB ObjectId first (`REST_002`) so garbage IDs never touch Redis or MongoDB.
2. `RestaurantCacheService.getActiveRestaurant()` executes **Cache-Aside with Single-Flight Locking**:
   - **Tier 1 (L1 Caffeine in JVM RAM):** Calls `l1Cache.get(id, loader)`. If hit $\rightarrow$ returns in microseconds. If 500 threads miss simultaneously for the same key, Caffeine locks per-key so only **1 thread** queries Redis/MongoDB (prevents Thundering Herd).
   - **Tier 2 (L2 Redis):** Checks `restaurant:cache:<id>`. If JSON exists $\rightarrow$ deserializes and populates L1 (2-min TTL). If `__NOT_FOUND__` sentinel exists $\rightarrow$ returns empty immediately (prevents Cache Penetration).
   - **Tier 3 (MongoDB Source of Truth):** On L2 miss, queries MongoDB, writes JSON to Redis with **30 min + random 0–180s jitter** (prevents Cache Avalanche), or writes `__NOT_FOUND__` for **30s** if missing/pending. If Redis is down, it catches the exception and degrades gracefully to MongoDB.
3. **Cross-Pod Consistency on Write:** When an owner updates the menu, `evict(id)` invalidates local L1, deletes the L2 Redis key, and publishes `restaurantId` to Redis Pub/Sub channel `cache:evict:restaurant`. Every pod's `RestaurantCacheInvalidationSubscriber` receives the broadcast and invalidates its own JVM L1 RAM.

### Journey D: Placing an Order — Idempotency, Outbox & Choreography Saga (`order-service` $\leftrightarrow$ `restaurant-service`)
1. **Command Entry (`POST /api/orders` with `Idempotency-Key` header):**
   - Client sends **only** `restaurantId` and `[{ itemId, quantity }]`—zero prices, zero `customerId`.
   - `OrderCommandService.placeOrder()` runs inside **one single PostgreSQL `@Transactional` boundary**:
     1. Validates `Idempotency-Key`, JWT `sub` UUID, 24-hex `restaurantId`, and duplicate `itemId` (`ORDER_002`).
     2. Canonicalizes items (sorted by `itemId`) and computes a **SHA-256 `requestHash`**.
     3. Checks `idempotency_records(idempotency_key, customer_id)`:
        - Exists + **same hash** $\rightarrow$ replays saved `201 Created` JSON with `X-Idempotent-Replay: true` (zero duplicate orders).
        - Exists + **different hash** $\rightarrow$ rejects with `400 ORDER_003` (tampering/bug).
     4. New key $\rightarrow$ atomically commits **3 writes together**: `orders` + `order_items` (`status = PENDING`, `totalAmount = 0.00`), `outbox_events` (`ORDER_CREATED`, `status = PENDING`), and `idempotency_records`.
2. **Outbox Relay (`OutboxRelayScheduler`):**
   - `@Scheduled` background worker runs `SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED`.
   - Publishes each event to Kafka topic `bitesync.order.events` using `orderId` as the **partition key** (guarantees strict FIFO per order). Waits for broker ACK (`acks=all`), then flips status to `PUBLISHED` (or `FAILED` after 5 retries to stop poison pills).
3. **Restaurant Validation (`restaurant-service`):**
   - `OrderEventsConsumer` (`groupId = restaurant-order-saga-group`) listens to `bitesync.order.events`, ignores non-`ORDER_CREATED` events, and calls `OrderValidationSagaService.processOrderCreated()`.
   - Checks MongoDB `processed_events` for idempotency.
   - **Intentionally bypasses L1/L2 cache** and reads live MongoDB to verify: restaurant exists, `status == ACTIVE`, every `itemId` exists, and `isAvailable == true`. Computes authoritative `BigDecimal` unit prices and total.
   - Publishes `RESTAURANT_ORDER_ACCEPTED` (with prices) or `RESTAURANT_ORDER_REJECTED` (with reason) to `bitesync.restaurant.events` keyed by `orderId`, and atomically `insert()`s the `eventId` into MongoDB `processed_events`.
4. **Saga Completion (`order-service`):**
   - `RestaurantEventsConsumer` (`groupId = order-saga-group`) consumes `bitesync.restaurant.events` and delegates to `OrderSagaService.handleRestaurantDecision()`.
   - Inside one `@Transactional` block: checks PostgreSQL `processed_events(event_id, 'order-saga-group')`, verifies order is still `PENDING`, transitions to **`CONFIRMED`** (saving real prices/total) OR runs compensating rollback to **`CANCELLED`** (saving `cancellationReason`), writes an **`ORDER_CONFIRMED` / `ORDER_CANCELLED`** row into `outbox_events`, and records `processed_events`.

### Journey E: CQRS Read Projection & Query API (`order-service`)
1. **Asynchronous Projection (`OrderReadModelProjector` $\rightarrow$ `OrderProjectionService`):**
   - Separate Kafka consumer group `order-query-projector` subscribes to `bitesync.order.events`.
   - On `ORDER_CREATED`: inserts a flat row into `order_summary_view` (`status = PENDING`, summed `itemCount`, serialized `itemsJson`). Includes an out-of-order guard so a late `ORDER_CREATED` never downgrades an already `CONFIRMED`/`CANCELLED` view.
   - On `ORDER_CONFIRMED` / `ORDER_CANCELLED`: upserts `order_summary_view` with `restaurantName`, `totalAmount`, priced `itemsJson`, and `cancellationReason`, while recording `(event_id, 'order-query-projector')` in `processed_events`.
2. **Zero-JOIN Queries (`GET /api/orders` & `GET /api/orders/{id}`):**
   - `OrderQueryController` $\rightarrow$ `OrderQueryService` (`@Transactional(readOnly = true)`) reads **exclusively** from `OrderSummaryViewRepository` (never touches `orders` or `order_items`).
   - Enforces strict ownership (`view.customerId == jwt.sub`) with an override for `ROLE_ADMIN`.

---

## 3. Step-by-Step Study Curriculum (Curated & Verified YouTube Videos)

Watch each topic's video **first**, then immediately open the listed BiteSync files in IntelliJ and trace them line by line.

### Module 1: Microservices Foundations, Eureka & Reactive API Gateway (Phase 1)
* **BiteSync Files to Open:** `pom.xml`, `discovery-server/DiscoveryServerApplication.java`, `api-gateway/application.yml`, `GatewaySecurityConfig.java`, `bitesync-common/.../GlobalExceptionHandler.java`, `BiteSyncCommonConstants.java`
* **Videos to Watch:**
  1. [Master Service Discovery in Microservices | Eureka and Java Spring Boot](http://www.youtube.com/watch?v=ecuEkmFs5Vk) — *ByteMonk (8 min)* — Crisp architectural explanation of Eureka registration, heartbeats, and `lb://` resolution.
  2. [What is Spring Cloud Gateway in Microservices?](http://www.youtube.com/watch?v=3XEpq_Thzdo) — *Java Guides (4 min)* — Fast overview of edge routing, predicates, and reactive WebFlux filters.
  3. [Implementing API GATEWAY in Simple ways | Microservices Tutorial Series](http://www.youtube.com/watch?v=dGDfTyNH-M4) — *Learn Code With Durgesh (26 min)* — Hands-on Spring Cloud Gateway + Eureka walkthrough.

### Module 2: Spring Security 6, Asymmetric RS256 JWT, Flyway & Redis Logout Blacklist (Phase 2)
* **BiteSync Files to Open:** `user-service/db/migration/V1`–`V4`, `SecurityConfig.java`, `TokenService.java`, `AuthController.java`, `TokenBlacklistService.java`, `api-gateway/.../BlacklistFilter.java`
* **Videos to Watch:**
  1. [Database Migration with Flyway, SpringBoot and PostgreSQL](http://www.youtube.com/watch?v=efree1FbtQA) — *Geekific (5 min)* — Clean refresher on Flyway versioning, checksums, and `ddl-auto: validate`.
  2. [Spring Security JWT: How to secure your Spring Boot REST APIs with JSON Web Tokens](http://www.youtube.com/watch?v=KYNR5js2cXE) — *Dan Vega (43 min)* — **Must-watch:** Uses the exact modern Spring Security 6 `JwtEncoder` + `JwtDecoder` + RSA `KeyPair` architecture we built in `user-service`.
  3. [Spring Security JWT - Asymmetric Encryption](http://www.youtube.com/watch?v=bLA7avkOpIQ) — *Ali Bouali (3h 24m — skim key chapters)* — Deep dive on asymmetric public/private key signing and RBAC in Spring Boot 3.
  4. [JWT Logout Explained | How to Invalidate JWT Before Expiry | Scenario based Interview Question](http://www.youtube.com/watch?v=3_p2SQtG2C0) — *CodeTav Management (18 min)* — Explains the exact interview question behind our Redis JWT blacklist + refresh token revocation.
  5. [How to Revoke a JWT Before It Expires | Spring Boot JWT Logout with Redis](http://www.youtube.com/watch?v=TiSbYSbq9BE) — *Prashant Sharma (55 min — optional code walkthrough)* — Mirrors our `TokenBlacklistService` + Redis TTL pattern.

### Module 3: CAP Theorem, Polyglot MongoDB, ESR Indexing & Cursor Pagination (Phase 3A)
* **BiteSync Files to Open:** `Restaurant.java`, `MenuItem.java` (`DECIMAL128`), `RestaurantCustomRepositoryImpl.java`, `RestaurantService.java`
* **Videos to Watch:**
  1. [CAP Theorem in System Design Interviews](http://www.youtube.com/watch?v=VdrEq0cODu4) — *Hello Interview (14 min)* — **Must-watch:** Exactly how Google/Meta interviewers expect you to discuss CP vs. AP trade-offs.
  2. [DB Indexing in System Design Interviews - B-tree, Geospatial, Inverted Index, and more!](http://www.youtube.com/watch?v=BHCSL_ZifI0) — *Hello Interview (14 min)* — Covers B-Trees, composite indexes, and even previews Geospatial indexes for Phase 4.
  3. [Speed Up MongoDB Queries: Indexing Best Practices & The ESR Rule (Guide)](http://www.youtube.com/watch?v=6NP5dDgQI8A) — *ProgrammingKnowledge (6 min)* — Directly explains our `{ cityKey: 1, status: 1, rating: -1, _id: -1 }` Equality-Sort-Range index.
  4. [API Pagination: Making Billions of Products Scrolling Possible](http://www.youtube.com/watch?v=14K_a2kKTxU) — *ByteByteGo (3 min)* + [Web API Pagination | Offset-based vs Cursor-based](http://www.youtube.com/watch?v=WUICbOOtAic) — *Ambient Coder (11 min)* — Explains why `(afterRating, afterId)` cursor pagination crushes `OFFSET` at scale.

### Module 4: Multi-Tier Hybrid Caching — Caffeine L1 + Redis L2 + Redis Pub/Sub (Phase 3A)
* **BiteSync Files to Open:** `CacheConfig.java`, `RestaurantCacheService.java`, `RestaurantCacheInvalidationSubscriber.java`, `bitesync-docs/hybrid-cache-strategy.md`
* **Videos to Watch:**
  1. [Caching in System Design Interviews w/ Meta Staff Engineer](http://www.youtube.com/watch?v=1NngTUYPdpI) — *Hello Interview (30 min)* — **Must-watch:** Covers Cache-Aside, Write-Through, Thundering Herd, Cache Penetration, Avalanche/TTL Jitter, and Eviction.
  2. [Redis Deep Dive w/ a Ex-Meta Senior Manager](http://www.youtube.com/watch?v=fmT5nlEkl3U) — *Hello Interview (31 min)* — Internals of Redis, data structures, TTLs, and Pub/Sub trade-offs.
  3. [Mastering Caffeine Cache with Java | Hands-on Deep Dive](http://www.youtube.com/watch?v=QWIPH0t-bcw) — *TheCodeAlchemist (1h — watch first 30 min)* — Hands-on Caffeine configuration, custom `Expiry`, single-flight `get(key, fn)`, and `recordStats()`.
  4. [How to use Redis Pub/Sub with Spring Boot](http://www.youtube.com/watch?v=bSe_JBSk5w4) — *Theo's Tech Tips (11 min)* — Mirrors our `RedisMessageListenerContainer` + `MessageListenerAdapter` cross-pod eviction setup.

### Module 5: Apache Kafka (KRaft Mode), Partitions, Replication & Consumer Groups (Phase 3B)
* **BiteSync Files to Open:** `docker-compose.infra.yml` (Kafka KRaft block), `order-service/application.yaml`, `KafkaTopicConfig.java`, `OrderEventsConsumer.java`, `RestaurantEventsConsumer.java`
* **Videos to Watch:**
  1. [Apache Kafka Fundamentals You Should Know](http://www.youtube.com/watch?v=-RDyEFvnTXI) — *ByteByteGo (5 min)* — Ultra-clear visual foundation.
  2. [Kafka Tutorial for Beginners | Everything you need to get started](http://www.youtube.com/watch?v=QkdkLdMBuL0) — *TechWorld with Nana (19 min)* — **Must-watch:** Topics, partitions, offsets, consumer groups, and KRaft vs. ZooKeeper.
  3. [Apache Kafka Data Plane Replication — Leaders, Followers, ISR & High Watermark](http://www.youtube.com/watch?v=9g5LdsnrM1o) — *DevXOps Tech (11 min)* — Answers the classic system design question *"What happens if a Kafka broker crashes?"*
  4. [Apache Kafka Consumer Group Protocol — Coordinators, Rebalancing & Offsets](http://www.youtube.com/watch?v=CRz_2ygimgA) — *DevXOps Tech (11 min)* — Answers *"What happens if a consumer pod crashes or scales up?"*
  5. [Message Queues in System Design Interviews w/ Meta Staff Engineer](http://www.youtube.com/watch?v=1ISRd0bS714) — *Hello Interview (27 min)* — How to defend Kafka choices in a Google/Meta System Design interview.

### Module 6: API Idempotency, Transactional Outbox, Choreography Saga & CQRS (Phase 3B)
* **BiteSync Files to Open:** `OrderCommandService.java`, `OutboxRelayScheduler.java`, `OutboxEventRepository.java`, `OrderValidationSagaService.java`, `OrderSagaService.java`, `OrderProjectionService.java`, `OrderReadModelProjector.java`, `OrderQueryService.java`
* **Videos to Watch:**
  1. **API Idempotency:** [Build a robust Payments service using Idempotency Keys](http://www.youtube.com/watch?v=m6DtqSb1BDM) — *Arpit Bhayani (17 min)* — **Must-watch:** Exact mental model for `Idempotency-Key` + payload fingerprinting + DB uniqueness constraints.
  2. **Transactional Outbox:** [What is the Transactional Outbox Pattern?](http://www.youtube.com/watch?v=5YLpjPmsPCA) — *Confluent (6 min)* + [Kafka: Outbox Pattern (Part 1) - Polling Strategy](http://www.youtube.com/watch?v=sgWmFGzHpdU) — *Concept && Coding by Shrayansh (19 min)* — Explains the Dual-Write problem and the exact `FOR UPDATE SKIP LOCKED` polling relay we wrote.
  3. **Choreography Saga:** [Saga Pattern Explained: Orchestration vs Choreography with Real Microservices Examples](http://www.youtube.com/watch?v=TJMkz4sZRdQ) — *Kartikeya Sharma (12 min)* + [Saga Design pattern Spring Boot Microservices Interview Questions](http://www.youtube.com/watch?v=Fe_qPM81hJ8) — *Code Decode (21 min)*.
  4. **CQRS:** [Mastering CQRS in Just 5 Minutes](http://www.youtube.com/watch?v=SvjdJoNPcHs) — *ByteMonk (6 min)* + [Microservice | CQRS Design Pattern with SpringBoot & Apache Kafka](http://www.youtube.com/watch?v=fzGZPf0FMao) — *Java Techie (59 min — skim architecture + projector code)*.

### Module 7: Unit Testing & Web-Layer Testing (Ongoing Practice)
* **BiteSync Files to Open:** `OrderCommandServiceTest.java`, `OrderValidationSagaServiceTest.java`, `OrderCommandControllerTest.java`, `OrderQueryControllerTest.java`
* **Videos to Watch:**
  1. [Unit Testing in Spring Boot with JUnit 5 and Mockito | Part 1](http://www.youtube.com/watch?v=XVFqOFKGeGM) — *CodeSnippet by Chetan (31 min)* — Pure service-layer testing (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `when`/`verify`).
  2. [Spring Boot | Controller Unit Testing | Mockito | MockMVC](http://www.youtube.com/watch?v=9-mX5MACs5U) — *TheCodeStorm (17 min)* — Sliced `@WebMvcTest` + `MockMvc` + `jsonPath`.
  3. [Spring Boot Testing Explained: JUnit, MockMvc, Mockito & Testcontainers](http://www.youtube.com/watch?v=BSKqYjX4YCU) — *Kopi C (8 min)* — High-level map of when to use each test slice.

---

## 4. Machine-Coding & Take-Home Interview Blueprints (From a Blank Screen)

In Google, Wells Fargo, or FinTech take-home / live coding rounds, interviewers rarely care if you remember an exact Spring Bean name—they evaluate whether you can assemble the **core invariant** cleanly from scratch. Memorize these **4 skeleton checklists**:

### Blueprint 1: Implement API Idempotency From Scratch
1. **Contract:** Accept `Idempotency-Key` header (validate non-blank, max length).
2. **Storage:** Table `idempotency_records` with `UNIQUE (idempotency_key, user_id)` storing `request_hash`, `http_status`, `response_body`.
3. **Fingerprint:** Canonicalize the request DTO (trim strings, sort arrays) $\rightarrow$ compute `SHA-256` hex hash.
4. **Branching inside `@Transactional`:**
   - Key found + hash matches $\rightarrow$ deserialize & replay `response_body` with `X-Idempotent-Replay: true`.
   - Key found + hash differs $\rightarrow$ reject (`400/409` conflict).
   - Key missing $\rightarrow$ execute business write + insert `idempotency_records` in the **same DB transaction**.

### Blueprint 2: Implement Transactional Outbox From Scratch
1. **Storage:** Table `outbox_events(id, aggregate_id, event_type, topic, payload, status, retry_count, created_at)` + partial index `WHERE status = 'PENDING'`.
2. **Write Path:** Inside the exact same `@Transactional` method as your business entity `save()`, insert the serialized event into `outbox_events` with `status = 'PENDING'`. **Never call `kafkaTemplate.send()` directly inside the HTTP request transaction.**
3. **Relay Worker:** `@Scheduled` method inside `@Transactional` fetching `LIMIT 50 FOR UPDATE SKIP LOCKED`, calling `kafkaTemplate.send(topic, aggregateId, payload).get(5, SECONDS)`, flipping to `PUBLISHED`, and flipping to `FAILED` after `MAX_RETRIES`.

### Blueprint 3: Implement Multi-Tier Cache (L1 + L2 + Eviction) From Scratch
1. **Read (`Cache-Aside`):** `L1.get(key, k -> checkL2OrDb(k))` (single-flight lock per key inside Caffeine).
2. **L2 Miss Protection:**
   - Positive hit $\rightarrow$ write JSON to Redis with `baseTTL + randomJitter` (beats Avalanche).
   - Not found $\rightarrow$ write `"__NOT_FOUND__"` sentinel with short `30s` TTL (beats Penetration).
   - Redis exception $\rightarrow$ catch & log warn, fall back directly to DB (graceful degradation).
3. **Write (`Evict-on-Write`):** Commit DB first $\rightarrow$ invalidate local L1 $\rightarrow$ delete L2 Redis key $\rightarrow$ publish `key` to Redis Pub/Sub so peer pods clear their L1.

### Blueprint 4: Implement CQRS + Idempotent Kafka Consumer From Scratch
1. **Deduplication Table:** `processed_events(event_id, consumer_group)` composite primary key.
2. **Write/Saga Consumer:** Check `existsById(eventId, group)` $\rightarrow$ validate state machine (`if (status != PENDING) return`) $\rightarrow$ apply mutation + save `processed_events` in one `@Transactional` block.
3. **Read Projector:** Dedicated consumer group projecting flat JSON/columns into `order_summary_view` with out-of-order upsert safety. Query controllers read **only** from `order_summary_view` (`readOnly = true`).

---

## 5. High-Frequency Kafka & Distributed Systems Interview Q&A

These cover the exact real-world failure scenarios interviewers drill into:

| Interview Question | BiteSync-Grounded Senior Answer |
| :--- | :--- |
| **1. ZooKeeper vs. KRaft — why did Kafka remove ZooKeeper?** | Legacy Kafka needed a separate ZooKeeper cluster for cluster metadata and leader election (two distributed systems to operate, slow failovers, partition limits). **KRaft** embeds a Raft consensus quorum directly inside Kafka brokers—simpler ops, faster controller failover, and support for millions of partitions. |
| **2. What if a Kafka producer crashes or retries on a network timeout?** | We configure `acks=all` (wait for all in-sync replicas) and `enable.idempotence=true` (Kafka assigns a Producer ID + sequence number so broker-level retries never write duplicate records to the partition log). |
| **3. How do you guarantee events for the same order are processed in order across 3 partitions?** | Every producer sends with **`key = orderId`**. Kafka hashes the key (`murmur2(key) % numPartitions`) so `ORDER_CREATED`, `ORDER_CONFIRMED`, and `ORDER_CANCELLED` for Order X always land in the **same partition** in strict FIFO order. |
| **4. What if a consumer pod crashes right after updating PostgreSQL, before committing its Kafka offset?** | Because we use `enable-auto-commit=false` + `ack-mode=record`, Kafka redelivers that message to another pod in the consumer group (**at-least-once delivery**). Our consumer checks `processed_events(event_id, consumer_group)` inside the same DB transaction and safely skips the duplicate (**effectively-once processing**). |
| **5. What happens when a new pod joins or a consumer pod dies (Rebalancing)?** | Kafka's Group Coordinator detects missed heartbeats (`session.timeout.ms`) and triggers a **Consumer Group Rebalance**, reassigning the dead pod's partitions to surviving pods. With 3 partitions, up to 3 pods can process in parallel; a 4th pod sits idle as hot standby. |
| **6. What if a "poison pill" message keeps crashing a consumer forever?** | Without a retry cap, a malformed message blocks the entire partition infinitely. On the **Outbox side**, we cap retries at `5` and mark the row `FAILED`. On the **Kafka Consumer side**, production configures Spring Kafka's `DefaultErrorHandler` with exponential backoff + a **Dead Letter Topic (DLT)** (`bitesync.order.events.DLT`) so bad messages are quarantined for inspection while healthy orders keep flowing. |
| **7. What if Kafka is completely down when a customer clicks "Place Order"?** | Checkout **still succeeds (`201 Created`)!** Because of the **Transactional Outbox**, the order and `outbox_events` row commit safely to PostgreSQL. Once Kafka recovers, `OutboxRelayScheduler` automatically drains the backlog with zero lost orders. |
| **8. Why not call `restaurant-service` over HTTP during `POST /api/orders`?** | Synchronous HTTP couples availability and latency—if `restaurant-service` stalls for 5 seconds, `order-service` threads exhaust and checkouts crash. Kafka + Choreography Saga decouples them with backpressure and independent scaling. *(We reserve synchronous RPC like OpenFeign/gRPC for cases where the caller genuinely cannot continue without an immediate answer—covered in Phase 4.)* |

---

## 6. Quick Status Clarity: What's Done vs. Phase 4 & Phase 5

| Concept | Status | Where It Lives |
| :--- | :---: | :--- |
| **`@Scheduled` Background Jobs** | ✅ Done | `OutboxRelayScheduler` (`order-service`) + `SchedulingConfig` |
| **Synchronous RPC (OpenFeign / gRPC)** | ⏳ Phase 4 | Automated role sync & internal blocking RPCs |
| **`@Async` & Java 21 Virtual Threads** | ⏳ Phase 4 | High-throughput blocking I/O concurrency |
| **Rate Limiting (Redis Token Bucket)** | ⏳ Phase 4 | Edge protection in `api-gateway` |
| **Resilience4j (Circuit Breaker, Bulkhead, TimeLimiter)** | ⏳ Phase 4 | Cascading failure protection |
| **ShedLock (Distributed Scheduler Lock)** | ⏳ Phase 4 | Guarantees cron jobs run on 1 pod across replicas |
| **WebSockets + Geospatial (`Redis GEO` / `2dsphere`) + HMAC Webhooks** | ⏳ Phase 4 | Live delivery tracking & third-party payment callbacks |
| **OpenTelemetry, Prometheus, Grafana, Testcontainers, CI/CD** | ⏳ Phase 5 | Full observability, real-container integration tests & pipelines |
| **High-Concurrency Load Testing (k6 / Gatling Script)** | ⏳ Phase 5 Capstone | Blast thousands of concurrent requests through Gateway, Caffeine/Redis, and Kafka Orders while watching Grafana dashboards live! |

---

## 7. Your 7-Day Consolidation Plan (Before Starting Phase 4)

Don't binge 15 hours of videos in one sitting. Pair **1 short video + 15 minutes reading your own IntelliJ code** per day:

- **Day 1 (Edge & Auth):** Watch *ByteMonk Eureka* + *Dan Vega RS256 JWT* + *CodeTav JWT Logout*. Trace `GatewaySecurityConfig` $\rightarrow$ `BlacklistFilter` $\rightarrow$ `AuthController` $\rightarrow$ `TokenService`.
- **Day 2 (Databases & Pagination):** Watch *Hello Interview CAP Theorem* + *DB Indexing* + *ESR Rule* + *Offset vs Cursor*. Trace `Restaurant.java` $\rightarrow$ `RestaurantCustomRepositoryImpl.java`.
- **Day 3 (Multi-Tier Caching):** Watch *Hello Interview Caching* + *Redis Deep Dive* + *Theo's Redis Pub/Sub*. Trace `CacheConfig` $\rightarrow$ `RestaurantCacheService` $\rightarrow$ `RestaurantCacheInvalidationSubscriber`.
- **Day 4 (Kafka Internals):** Watch *TechWorld with Nana Kafka* + *DevXOps Replication* + *Consumer Group Rebalancing*. Open Kafka UI (`:8090`) and inspect your 3 partitions and 3 consumer groups live.
- **Day 5 (Idempotency & Outbox):** Watch *Arpit Bhayani Idempotency* + *Confluent Outbox* + *Shrayansh Outbox Polling*. Trace `OrderCommandService` $\rightarrow$ `OutboxEventRepository` (`SKIP LOCKED`) $\rightarrow$ `OutboxRelayScheduler`.
- **Day 6 (Saga & CQRS):** Watch *Kartikeya Saga* + *ByteMonk CQRS*. Trace `OrderEventsConsumer` $\rightarrow$ `OrderValidationSagaService` $\rightarrow$ `OrderSagaService` $\rightarrow$ `OrderReadModelProjector` $\rightarrow$ `OrderQueryService`.
- **Day 7 (Whiteboard Test):** Close IntelliJ and draw the Section 1 architecture + Journey D (Order Saga) on a blank sheet of paper from memory. Anywhere you get stuck is the exact 10-minute spot to re-read.