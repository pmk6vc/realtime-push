# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a realtime messaging infrastructure built with Micronaut (Java 21), WebSockets, and Citus (distributed PostgreSQL). The architecture supports horizontal scaling with multi-tenant messaging, JWT authentication via Envoy proxy, and Keycloak for identity management.

## Build & Development Commands

### Building and Testing
```bash
# Build the application
./gradlew build

# Run unit tests only
./gradlew test

# Run integration tests (builds Docker images first)
./gradlew integrationTest

# Run a single test
./gradlew test --tests "messaging.ConnectionRegistryTest"

# Run full verification suite (unit tests + integration tests + checkstyle)
./gradlew check
```

### Code Quality
```bash
# Run checkstyle
./gradlew checkstyleMain checkstyleTest checkstyleIntegrationTest

# Auto-format code with Spotless (Google Java Format)
./gradlew spotlessApply

# Check formatting without fixing
./gradlew spotlessCheck
```

### Docker Operations
```bash
# Build Docker image locally
./gradlew jibDockerBuild

# Build Envoy image (required for integration tests)
docker build -t realtime-envoy:it -f envoy/envoy.dockerfile envoy

# Start full stack locally
./gradlew dockerComposeUp

# Stop stack
./gradlew dockerComposeDown
```

## Architecture

### Core Components

**MessagingServer** (`src/main/java/messaging/MessagingServer.java`)
- WebSocket endpoint at `/chat`
- Handles WebSocket lifecycle: `@OnOpen`, `@OnClose`, `@OnMessage`, `@OnError`
- Extracts user ID from request headers via `HeaderUserIdExtractor`
- Registers/unregisters sessions in `ConnectionRegistry`
- Currently broadcasts to local server connections; TODOs indicate future Kafka fanout

**ConnectionRegistry** (`src/main/java/messaging/ConnectionRegistry.java`)
- Singleton managing active WebSocket sessions
- Enforces single-connection-per-user: new connections replace old ones
- Provides broadcast methods with targeting/exclusion support
- Thread-safe via `ConcurrentHashMap`

**HeaderUserIdExtractor** (`util/HeaderUserIdExtractor.java`)
- Currently extracts user ID from `X-User-Id` header
- TODO: Extract from JWT claims instead

### Infrastructure Stack

**Envoy Proxy**
- Terminates TLS, validates JWT tokens from Keycloak
- Forwards authenticated requests to Micronaut app instances
- Configured via `envoy/envoy.template.yaml` with environment variable substitution
- JWT verification against Keycloak's JWKS endpoint

**Keycloak**
- Identity provider with pre-configured realm "chat"
- Public client "chat-frontend" for web applications
- Backed by dedicated PostgreSQL database

**Citus Cluster**
- Distributed PostgreSQL (1 coordinator + 3 workers)
- Messages table distributed by `channel_id` for conversation locality
- Users and channels are reference tables (replicated across workers)
- Initialized via scripts in `db/init-*`:
  - `db/init-common/`: Extensions and common setup for all nodes
  - `db/init-master/`: Coordinator-specific setup (adds workers)
  - `db/init-runner/`: Orchestrates execution in Docker entrypoint

**Database Migrations**
- Managed by Flyway (runs on application startup)
- Located in `src/main/resources/db/migration/`
- Schema:
  - `users`: Reference table (user_id UUID PK)
  - `channels`: Reference table (channel_id UUID PK)
  - `messages`: Distributed table (PK: channel_id + message_id, distributed by channel_id)

### Testing Architecture

**Test Structure**
- Unit tests: `src/test/java/` (use Mockito, JUnit 5)
- Integration tests: `src/integrationTest/java/` (separate source set)
- Integration tests require Docker images: `realtime-messaging:it` and `realtime-envoy:it`

**IntegrationInfraExtension** (`src/integrationTest/java/testutils/IntegrationInfraExtension.java`)
- JUnit 5 extension that stands up entire infrastructure ONCE per test run
- Starts containers: Citus cluster, Keycloak (+ its DB), Micronaut app, Envoy
- Creates test users: "alice" and "bob" with passwords
- Provides helper methods for token generation, HTTP clients, container access
- Automatically tears down on test suite completion

**Test Categories**
- `src/integrationTest/java/messaging/`: Component tests (Micronaut context, no full stack)
- `src/integrationTest/java/e2e/`: End-to-end tests (full stack via Envoy)

## Configuration

**Environment Profiles**
- `application.yaml`: Base config (logger levels, endpoints)
- `application-dev.yaml`: Dev datasource pointing to `citus_master`
- `application-test.yaml`: Test-specific overrides

**Environment Variables** (see `.env` and `docker-compose.yaml`)
- `CITUS_USER`, `CITUS_PASSWORD`, `CITUS_DB`: Database credentials
- `KC_*`: Keycloak configuration
- `UPSTREAM_HOST`, `UPSTREAM_PORT`: Micronaut app backend for Envoy
- `ENVOY_PORT`, `ENVOY_ADMIN_PORT`: Envoy listener ports

## Development Patterns

**Adding Database Migrations**
1. Create new file in `src/main/resources/db/migration/` following naming: `V<timestamp>__description.sql`
2. Migrations run automatically on app startup via Flyway
3. For Citus-specific DDL (creating distributed/reference tables), see existing migration for examples
4. Foreign keys referencing reference tables require `SET LOCAL citus.multi_shard_modify_mode TO 'sequential'`

**Testing with Keycloak Auth**
1. Use `IntegrationInfraExtension` to inject `Infra` into test methods
2. Call `infra.passwordGrant("alice", "alice!")` to get JWT token
3. Pass token in WebSocket upgrade request headers or HTTP Authorization header

**WebSocket Client Testing**
- `AbstractWebSocketClientTemplate`: Base class for WebSocket test clients
- `E2ETestWebSocketClient`: Uses OkHttp, connects via Envoy (full auth flow)
- `MicronautTestWebSocketClient`: Uses Micronaut HTTP client (component tests)

## Important Implementation Notes

- **Single connection per user**: `ConnectionRegistry` automatically closes previous session when user reconnects
- **Message persistence**: Currently messages are NOT persisted to database (see TODO in `MessagingServer.onSessionMessage`)
- **Broadcast scope**: Current implementation only broadcasts to users connected to same app instance; Kafka fanout is planned
- **Citus primary keys**: Must include distribution column (e.g., messages PK includes `channel_id`)
- **Integration test parallelism**: Disabled (`maxParallelForks = 1`) to prevent port conflicts with Docker containers

## Docker Compose Services

When running `./gradlew dockerComposeUp`, the following services start:
- `keycloak-db`: PostgreSQL for Keycloak
- `keycloak`: Identity provider (port from env: KC_HOST_PORT)
- `citus_master`: Citus coordinator (port from env: MASTER_PORT)
- `citus_worker_1/2/3`: Citus worker nodes
- `messaging_app`: Micronaut application instances (scaled to 3 replicas)
- `envoy`: Load balancer and auth gateway (port from env: ENVOY_PORT)