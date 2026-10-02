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
- The legacy `hmdp` database schema, table names, HTTP routes and Redis keys remain compatible until their dedicated migration stages.
- End-to-end behavior and performance have not yet been verified.

See the [domain model](docs/domain-model.md), [current-state audit](docs/refactor/00-current-state.md), [target architecture](docs/refactor/01-target-architecture.md), [migration plan](docs/refactor/02-migration-plan.md), [Phase 0.5 verification record](docs/refactor/03-phase-0.5-baseline.md), [Phase 1B verification record](docs/refactor/04-phase-1b-correctness.md), [Phase 1C verification record](docs/refactor/05-phase-1c-identity.md), [Phase 2A verification record](docs/refactor/06-phase-2a-api-errors-validation.md), [Phase 2B verification record](docs/refactor/07-phase-2b-session-lifecycle.md), [Phase 2C verification record](docs/refactor/08-phase-2c-authorization.md), [Phase 2D verification and API contract](docs/refactor/09-phase-2d-api-models.md) and [legacy compatibility notes](docs/learning/legacy-compatibility.md).

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

## Build and test

```bash
./mvnw clean test
```

The default test suite contains only tests that do not require MySQL or Redis. The legacy data-loading and Redis experiments are retained as manual integration helpers. Run them only against an isolated local environment:

```bash
RUN_MANUAL_INTEGRATION_TESTS=true ./mvnw -Dtest=CampusHubApplicationTests test
```

## Run

```bash
./mvnw spring-boot:run
```

The service listens on port `8081` by default. Redis Token authentication and role/resource authorization are implemented, but the V002 migration and database-backed authorization flow still require verification against an isolated real MySQL environment. Full end-to-end behavior, reliable-consumer recovery, performance and deployment support are still scheduled work; consult the migration plan before treating these capabilities as complete.
