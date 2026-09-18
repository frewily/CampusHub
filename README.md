# CampusHub

CampusHub is a gradual refactoring of a legacy local-services teaching project into a campus life and merchant services platform. The repository is currently establishing a reproducible baseline before changing the domain model or adding infrastructure.

## Current status

- Phase 0 repository audit is complete.
- Phase 0.5 build, test and Redis Stream startup baseline is complete.
- Phase 1A defines the target domain model; Phase 1B correctness work has not started.
- The existing package and database names remain `hmdp` until the dedicated identity migration stage.
- End-to-end behavior and performance have not yet been verified.

See the [domain model](docs/domain-model.md), [current-state audit](docs/refactor/00-current-state.md), [target architecture](docs/refactor/01-target-architecture.md), [migration plan](docs/refactor/02-migration-plan.md) and [Phase 0.5 verification record](docs/refactor/03-phase-0.5-baseline.md).

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

The application creates the Redis Stream `stream.orders` and consumer group `g1` when the order consumer is enabled.

## Build and test

```bash
./mvnw clean test
```

The default test suite contains only tests that do not require MySQL or Redis. The legacy data-loading and Redis experiments are retained as manual integration helpers. Run them only against an isolated local environment:

```bash
RUN_MANUAL_INTEGRATION_TESTS=true ./mvnw -Dtest=HmDianPingApplicationTests test
```

## Run

```bash
./mvnw spring-boot:run
```

The service listens on port `8081` by default. Authentication, authorization, Feed correctness, order reliability and deployment support are still scheduled work; consult the migration plan before treating these capabilities as complete.
