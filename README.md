# CampusHub

CampusHub is a gradual refactoring of a legacy local-services teaching project into a campus life and merchant services platform.

## Current status

- Phase 0 repository audit is complete.
- Phase 0.5 build, test and Redis Stream startup baseline is complete.
- Phase 1A defines the target domain model; Phase 1B repairs the confirmed Feed, follow, logical-expiry cache and asynchronous-order correctness defects.
- Phase 1C adopts the `io.github.frewily.campushub` root package and CampusHub application identity.
- Phase 2A introduces typed API errors, Bean Validation and centralized exception handling.
- Phase 2B adds verification-code throttling, one-time code consumption, Redis Token logout and request-context cleanup.
- Phase 2C introduces stateless Spring Security authentication, account status, USER/MERCHANT/ADMIN roles and merchant resource ownership checks.
- Phase 2D separates shop/post/promotion write requests and user-profile responses from persistence entities, with explicit field mappings and request validation.
- Phase 3A enforces flash-sale eligibility, status, time window, inventory and retry rules in Redis/Lua. `ACCEPTED` means an event was queued in Redis, not that an order was persisted or paid.
- Phase 3B adds UUID consumers, bounded pending recovery/retries, owner-checked failure archival and operator-only same-ID redrive. Failed reservations are retained for DB review, never automatically refunded. Redis Stream is retained by ADR 0001.
- Phase 3C adds owner-only order status queries and cancellation of persisted unpaid flash-sale orders. Database cancellation, stock return and an outbox commit together; leased recovery compensates Redis separately without removing one-user-one-order identity.
- Phase 4A uses one bounded cache-aside strategy for shop detail: physical TTL jitter, negative caching, token-owned rebuild and epoch-fenced publication. Shop writes commit a durable invalidation outbox; Redis invalidation runs after commit and is retried by the scheduler.
- The legacy `hmdp` database schema, table names, HTTP routes and Redis keys remain compatible until their dedicated migration stages.
- Phase 4A intentionally versions the shop-detail cache format; other business Redis keys remain unchanged.
- Isolated MySQL/Redis business-chain integration is verified on the local versions below; real-network deployment, target MySQL 8 compatibility and performance remain unverified.

See the [domain model](docs/domain-model.md), [migration plan](docs/refactor/02-migration-plan.md), [Phase 2D API contract](docs/refactor/09-phase-2d-api-models.md), [flash-sale admission contract](docs/refactor/10-phase-3a-flash-sale-admission.md), [order lifecycle](docs/refactor/12-phase-3c-order-lifecycle.md), [Phase 4A cache governance](docs/refactor/13-phase-4a-shop-cache-governance.md) and [legacy compatibility notes](docs/learning/legacy-compatibility.md). Earlier phase records remain under `docs/refactor/`.

Write requests now accept only documented business fields. Extra entity fields are ignored; missing or invalid required fields return HTTP 400 with `VALIDATION_FAILED`. See the Phase 2D API contract before reusing full legacy entity payloads.

## Requirements

- JDK 8
- MySQL 8
- Redis 6 or newer

The Maven Wrapper downloads Maven 3.9.9 on first use. The current build intentionally rejects other JDK versions so that an unsupported compiler does not produce misleading Lombok errors.

## Configuration

Copy `.env.example` to a local `.env` file and replace the example password. `.env` is documentation for local shells and is not loaded automatically by Spring Boot.

```bash
set -a
source .env
set +a
```

Do not commit `.env` or real credentials.

Custom `DB_URL` values must keep `serverTimezone=UTC&forceConnectionTimeZoneToSession=true` so SQL timestamps and the outbox scheduler share UTC. Client-side time decoding alone does not set the database session timezone.

Initialize the existing legacy schema before starting the application:

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/hmdp.sql
```

Apply the versioned Phase 1B constraints after the legacy schema. The migration is safe to run again after it succeeds, but it deliberately fails if duplicate follow or voucher-order rows already violate the new business rules:

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V001__add_business_unique_constraints.sql
```

Apply the Phase 2C identity and merchant-authorization schema after V001:

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V002__add_identity_and_merchant_authorization.sql
```

Historical shops remain platform-managed with a `NULL` merchant owner until a trusted administrative process assigns them.

Apply the Phase 3C cancellation outbox before enabling the new order endpoints or reconciler:

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V003__add_order_cancellation_outbox.sql
```

`ORDER_CANCELLATION_RECONCILER_ENABLED` defaults to true. Recovery scans every 5 seconds with a 30-second lease and 10-attempt budget. Cancellation HTTP success means the database committed, not that Redis stock is already returned. Query `GET /voucher-order/{id}?voucherId={voucherId}` for the separate compensation state; cancel with `POST /voucher-order/{id}/cancel?voucherId={voucherId}`. Keep string `orderId` from admission responses to avoid JavaScript integer precision loss; legacy numeric `data` remains. No payment, refund or auto-expiry cancellation is implemented.

Apply the Phase 4A cache invalidation outbox before using shop create/update:

```bash
mysql -u "$DB_USERNAME" -p hmdp < src/main/resources/db/migration/V004__add_shop_cache_invalidation_outbox.sql
```

`SHOP_CACHE_INVALIDATION_WORKER_ENABLED` defaults to true (every 5 seconds, up to 32 pending shops). Disabling the worker does not disable the immediate after-commit Redis attempt. Writes succeed when the database commits; they do not guarantee immediate cache coherence. Redis outage, malformed cache state or exhausted rebuild contention returns typed `SHOP_STATE_UNAVAILABLE` 503 on detail reads, not a fake missing shop or unrestricted DB fallback. Details have 60–75 second physical TTLs; negative entries have 10–15 seconds. These are unmeasured defaults, not an SLA.

The new `cache:shop:v2:` format ignores old `cache:shop:` values. Stop legacy warmers/writers before rollout; mixed old/new binaries are not a consistency guarantee. Epoch keys must not be independently removed/expired. No broad cleanup of old no-TTL keys is performed. See the Phase 4A runbook for durable recovery and key-retention requirements.

The application creates the Redis Stream `stream.orders` and consumer group `g1` when the order consumer is enabled.

`ORDER_CLAIM_IDLE_MS` defaults to 60000 (minimum 1000); `ORDER_MAX_ATTEMPTS` defaults to 5 (range 1–100, including the first attempt and crash attempts). Claim idle must exceed normal transaction latency. These are unmeasured defaults, not production tuning. See the [Phase 3B recovery and redrive runbook](docs/refactor/11-phase-3b-reliable-order-consumption.md) and [message-broker ADR](docs/adr/0001-order-message-broker.md). Do not trim uncompleted source messages or erase consumers with pending entries. Retention, capacity alerts, ACLs and persistence/restore remain deployment prerequisites.

## Build and test

```bash
./mvnw clean test
```

The default test suite contains only tests that do not require MySQL or Redis. The legacy data-loading and Redis experiments are retained as manual integration helpers. Run them only against an isolated local environment:

```bash
RUN_MANUAL_INTEGRATION_TESTS=true ./mvnw -Dtest=CampusHubApplicationTests test
```

The Phase 3A Redis script tests start a separate, non-persistent local Redis process and require `redis-server` on `PATH`:

```bash
./mvnw -Dtest=FlashSaleRedisScriptIT test
```

Phase 3B consumer recovery and redrive tests also own an isolated Redis process:

```bash
./mvnw -Dtest=OrderStreamRedisIT,FlashSaleRedisScriptIT test
```

Phase 3B passed 107 default tests (0 failures/errors, 4 manual cases skipped) and 26 isolated Redis tests on Redis 8.6.2 under JDK 8, with the same command-line Surefire override. At that checkpoint, real MySQL, Redis 6, persistence/failover and performance had not been verified. The Phase 3B Spring worker recovery IT uses a mock order persistence service; later real-DB evidence is described below.

These Phase 3A/3B figures are historical phase evidence; Phase 3C adds an isolated database integration suite and current evidence in its [stage record](docs/refactor/12-phase-3c-order-lifecycle.md).

Phase 3C explicit suites own their processes and synthetic data. They require `redis-server` and, for the database suite, `mysqld` on PATH (or `REDIS_SERVER_BINARY` / `MYSQLD_SERVER_BINARY`):

```bash
./mvnw -Dmaven-surefire-plugin.version=3.1.2 \
  -Dtest=OrderCancellationRedisIT,OrderStreamRedisIT,FlashSaleRedisScriptIT test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 -Dtest=OrderLifecycleMySqlRedisIT test
```

The database suite was verified on MySQL 9.6.0 with a minimal synthetic schema, real MyBatis/transactions/Redisson locks, and V001/V002/V003 forward/repeated migrations. It does not migrate historical production data or confirm MySQL 8 compatibility. Its HTTP coverage uses MockMvc with the real token filter and business chain, not a deployed network server; full security-chain route tests separately use mocked dependencies. Redis ACL partial-execution recovery is tested on Redis 8.6.2. Redis 6, persistence/failover and actual load testing remain unverified.

Phase 3C final verification: 143 default tests (0 failures/errors, 4 designed skips), 35 isolated Redis tests and 15 isolated MySQL/Redis integration tests (0 failures/errors/skips).

Phase 4A adds test-owned Redis and MySQL/Redis cache suites, including real transaction rollback, outbox failure, epoch fencing and ACL partial-execution recovery:

```bash
./mvnw -Dmaven-surefire-plugin.version=3.1.2 -Dtest=ShopCacheRedisIT test
./mvnw -Dmaven-surefire-plugin.version=3.1.2 -Dtest=ShopCacheMySqlRedisIT test
```

The cache MySQL IT installs real transaction interception but not method security or a network server; authorization/HTTP contracts are separately covered by default tests. The shop reader exposes process-local diagnostic counts for window-based measurement, not a measured production hit rate or metrics endpoint. No actual load test has been performed.

Phase 4A final verification: 162 default tests (0 failures/errors, 4 designed skips) and 72 isolated integration tests (0 failures/errors/skips): 13 cache Redis, 35 order Redis, 9 cache MySQL/Redis and 15 order MySQL/Redis regression cases.

## Run

```bash
./mvnw spring-boot:run
```

The service listens on port `8081` by default. Apply all migrations first. V002 backfill is verified only on synthetic MySQL 9.6.0 data; full historical migration and deployment smoke tests remain required. New flash-sale activity creation also requires the existing tables and Redis publication; an uncertain post-commit Redis failure may leave the database activity saved. Order query/cancellation and isolated business-chain recovery are implemented, while payment/fulfillment, performance and deployment support remain scheduled work. Consult the migration plan and Phase 3C recovery boundaries before deployment.
