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
- The legacy `hmdp` database schema, table names, HTTP routes and Redis keys remain compatible until their dedicated migration stages.
- End-to-end behavior and performance have not yet been verified.

See the [domain model](docs/domain-model.md), [migration plan](docs/refactor/02-migration-plan.md), [Phase 2D API contract](docs/refactor/09-phase-2d-api-models.md), [Phase 3A verification and flash-sale contract](docs/refactor/10-phase-3a-flash-sale-admission.md) and [legacy compatibility notes](docs/learning/legacy-compatibility.md). Earlier phase records remain under `docs/refactor/`.

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

Phase 3B passed 107 default tests (0 failures/errors, 4 manual cases skipped) and 26 isolated Redis tests on Redis 8.6.2 under JDK 8, with the same command-line Surefire override. Real MySQL, Redis 6 compatibility, persistence/failover and performance remain unverified. The Spring worker recovery IT uses a mock order persistence service.

The recovered Phase 3A checkout passed 96 default tests (4 manual external-service cases skipped) and 10 isolated Redis script tests under JDK 8. To use the available local dependency cache for that verification, Surefire `3.1.2` was selected via a command-line property; the project POM was not changed. Real MySQL end-to-end and performance verification remain pending.

## Run

```bash
./mvnw spring-boot:run
```

The service listens on port `8081` by default. Redis Token authentication and role/resource authorization are implemented, but the V002 migration and database-backed authorization flow still require verification against an isolated real MySQL environment. New flash-sale activity creation also requires the existing database tables and Redis publication; an uncertain post-commit Redis failure may leave the database activity saved. Consumer recovery is verified within the isolated Redis/mock-persistence scope, not full MySQL end-to-end. Order status/fulfillment, performance and deployment support remain scheduled work; consult the migration plan before treating these capabilities as complete.
