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

## Implementation TODO List

This section tracks remaining work to achieve production-ready distributed messaging. Last updated: 2026-01-08

### Current Status Summary

**✅ What's Production-Ready:**
- WebSocket connection management with sticky routing (Envoy RING_HASH)
- User authentication via Keycloak with JWT validation in Envoy
- Citus distributed database with proper schema (messages distributed by channel_id)
- Single-instance message broadcasting
- Comprehensive integration test infrastructure

**❌ Critical Gaps:**
- Messages NOT persisted to database
- No Kafka integration (messages don't reach users on other instances)
- No session failover/redistribution on instance failure
- No message ordering or durability guarantees across instances

### Phase 1: Core Distributed Messaging (CRITICAL PATH)

These items are **required** for multi-instance message delivery to work correctly:

1. **Message Persistence Layer**
   - [ ] Add Micronaut Data JDBC dependency to `build.gradle.kts`
   - [ ] Create `Message` entity class mapping to `messages` table
   - [ ] Create `MessageRepository` interface with `@JdbcRepository`
   - [ ] Inject repository into `MessagingServer`
   - [ ] Implement database insert in `onSessionMessage()` before broadcasting
   - [ ] Handle database errors and return error response to sender
   - [ ] Add unit tests for repository
   - [ ] Add integration test verifying messages written to Citus

2. **Kafka Producer Integration**
   - [ ] Add Kafka client dependencies to `build.gradle.kts` (kafka-clients, micronaut-kafka)
   - [ ] Configure Kafka bootstrap servers in `application.yaml` (environment-specific)
   - [ ] Create `MessageEvent` class as Kafka payload (contains: channelId, messageId, senderId, body, sentAt)
   - [ ] Create `@KafkaClient` producer interface
   - [ ] Add Kafka container to `docker-compose.yaml` and integration test infra
   - [ ] Publish to Kafka topic after successful database insert
   - [ ] Add producer error handling (retry logic, dead letter queue)
   - [ ] Add integration test verifying Kafka publish

3. **Transactional Outbox Pattern**
   - [ ] Create outbox table migration: `outbox (id UUID PK, aggregate_id UUID, event_type TEXT, payload JSONB, created_at TIMESTAMPTZ)`
   - [ ] Implement transactional write: single transaction writes both message + outbox entry
   - [ ] Create scheduled job (`@Scheduled`) to poll outbox and publish to Kafka
   - [ ] Delete outbox entries after successful Kafka publish
   - [ ] Add retry logic for failed publishes
   - [ ] Add integration test for outbox pattern (crash recovery scenario)

4. **Kafka Consumer Integration**
   - [ ] Create `@KafkaListener` consumer class subscribed to message topic
   - [ ] Parse `MessageEvent` from Kafka records
   - [ ] Look up channel members from database (or cache)
   - [ ] Call `ConnectionRegistry.broadcast()` to send to local WebSocket connections
   - [ ] Handle deserialization errors gracefully
   - [ ] Configure consumer group ID (all instances same group = compete for messages)
   - [ ] Add integration test with multiple app instances verifying cross-instance delivery

5. **Message Fanout Validation**
   - [ ] Add end-to-end test: Alice on instance 1, Bob on instance 2, verify Bob receives Alice's message
   - [ ] Test message ordering within single channel
   - [ ] Test concurrent messages from multiple senders
   - [ ] Verify all connected users in a channel receive broadcasts

### Phase 2: Resilience & Production Readiness

6. **Session State Management (Redis)**
   - [ ] Add Redis dependency (`micronaut-redis`)
   - [ ] Add Redis container to docker-compose and integration tests
   - [ ] Create `SessionStore` abstraction (interface: register, lookup, remove)
   - [ ] Implement Redis-backed session store with TTL
   - [ ] Store: userId -> {instanceId, sessionId, lastHeartbeat, channelIds}
   - [ ] Update session registry to write through to Redis
   - [ ] Add background job to expire stale sessions
   - [ ] Add integration tests for session state persistence

7. **Health Checks & Circuit Breakers**
   - [ ] Implement `/health` endpoint exposing: DB connection, Kafka connectivity, Redis connection
   - [ ] Configure Envoy health checks pointing to `/health`
   - [ ] Add graceful shutdown hook to drain connections before terminating
   - [ ] Implement circuit breaker for Kafka producer (fail open if Kafka down)
   - [ ] Add metrics endpoint (`/metrics`) with Micrometer
   - [ ] Expose: active connections count, messages sent/received, DB query times

8. **Session Failover & Redistribution**
   - [ ] Design failover strategy: active-active or active-passive?
   - [ ] Implement Envoy health-check based rerouting (remove unhealthy instances from ring)
   - [ ] Add client-side reconnection logic (exponential backoff)
   - [ ] Implement server-sent ping/pong for connection liveness
   - [ ] Test scenario: kill instance, verify clients reconnect to healthy instance
   - [ ] Consider: session migration (hard) vs. client reconnection (simpler)

9. **Message Ordering Guarantees**
   - [ ] Document ordering semantics: per-channel total order vs. causal order
   - [ ] Kafka topic partitioning: partition by `channel_id` for per-channel ordering
   - [ ] Add message sequence numbers or vector clocks if needed
   - [ ] Test concurrent message delivery maintains order within channel
   - [ ] Add integration test with multiple senders in same channel

10. **Error Handling & Retries**
    - [ ] Add retry logic for transient DB failures (Micronaut Retry annotation)
    - [ ] Implement exponential backoff for Kafka publish failures
    - [ ] Add dead letter queue for undeliverable messages
    - [ ] Gracefully handle WebSocket send failures (log + continue)
    - [ ] Return error messages to sender if message rejected
    - [ ] Add integration tests for error scenarios

### Phase 3: Testing & Observability

11. **End-to-End Multi-Instance Tests**
    - [ ] Extend `IntegrationInfraExtension` to support N app instances
    - [ ] Test: 3 instances, 10 users distributed across instances, broadcast to all
    - [ ] Test: instance crash during message send, verify recovery
    - [ ] Test: network partition between app and Kafka, verify message buffering
    - [ ] Performance test: 1000 concurrent connections, measure latency

12. **Message Persistence Tests**
    - [ ] Test: send message, verify written to Citus with correct channel_id
    - [ ] Test: query message history API returns messages in order
    - [ ] Test: distributed query across Citus workers returns correct results
    - [ ] Test: foreign key constraints enforced (invalid user_id rejected)

13. **Distributed Transaction Tests**
    - [ ] Test: outbox pattern - crash after DB write but before Kafka publish, verify eventual publish
    - [ ] Test: idempotency - duplicate Kafka message doesn't create duplicate DB entry
    - [ ] Test: transaction rollback on Kafka publish failure

14. **Failover Scenario Tests**
    - [ ] Test: graceful shutdown - connections drained, no message loss
    - [ ] Test: ungraceful shutdown (SIGKILL) - clients reconnect, Redis state preserved
    - [ ] Test: Envoy removes unhealthy instance from ring hash
    - [ ] Test: session state survives app restart (via Redis)

15. **Distributed Tracing**
    - [ ] Add OpenTelemetry dependencies
    - [ ] Add Jaeger container to docker-compose
    - [ ] Configure trace context propagation: WebSocket -> DB -> Kafka -> Consumer -> WebSocket
    - [ ] Add trace IDs to logs
    - [ ] Create dashboard showing end-to-end message latency

16. **Load Testing**
    - [ ] Create JMeter or Gatling test suite
    - [ ] Test: 10,000 concurrent WebSocket connections
    - [ ] Test: 1,000 messages/second throughput
    - [ ] Measure: p50, p95, p99 latency for message delivery
    - [ ] Identify bottlenecks (DB, Kafka, network, etc.)

### Phase 4: Features & Deployment

17. **Message History API**
    - [ ] Create REST endpoint: `GET /channels/{channelId}/messages?limit=50&before={messageId}`
    - [ ] Implement pagination using Citus distributed queries
    - [ ] Add cursor-based pagination for efficient traversal
    - [ ] Return messages in descending order (newest first)
    - [ ] Add authentication check (user must be member of channel)
    - [ ] Add integration test for message history retrieval

18. **Channel Membership Management**
    - [ ] Create `channel_members` table (distributed by channel_id)
    - [ ] Create REST endpoints: join channel, leave channel, list members
    - [ ] Enforce authorization: only members can send/receive messages in channel
    - [ ] Broadcast membership changes to connected users
    - [ ] Add integration tests for membership operations

19. **Kubernetes Deployment Manifests**
    - [ ] Create `k8s/` directory with manifests
    - [ ] Deployment: Micronaut app with 3 replicas
    - [ ] Service: ClusterIP for app, LoadBalancer for Envoy
    - [ ] ConfigMaps: Envoy config, app config
    - [ ] Secrets: DB credentials, Kafka credentials, Keycloak secrets
    - [ ] StatefulSet: Kafka cluster (or use managed Kafka)
    - [ ] PersistentVolumeClaims: Citus data, Kafka logs
    - [ ] Ingress: TLS termination, routing rules
    - [ ] HorizontalPodAutoscaler: scale based on CPU/memory/connection count
    - [ ] NetworkPolicy: restrict traffic between services

20. **Production Monitoring & Alerting**
    - [ ] Set up Prometheus for metrics collection
    - [ ] Set up Grafana dashboards: connection count, message throughput, latency percentiles, error rates
    - [ ] Configure alerts: high error rate, high latency, instance down, DB connection failures
    - [ ] Add PagerDuty/Opsgenie integration for critical alerts
    - [ ] Document runbooks for common failure scenarios

21. **Documentation & Operations**
    - [ ] Write deployment guide for Digital Ocean Kubernetes
    - [ ] Document monitoring and alerting setup
    - [ ] Create troubleshooting guide for common issues
    - [ ] Document backup and disaster recovery procedures
    - [ ] Write performance tuning guide (Kafka, Citus, connection limits)

### Priority Recommendation

**Start with Phase 1 in order** (items 1-5). You cannot have reliable distributed messaging without:
1. Persisting messages to Citus (enables history, recovery)
2. Publishing to Kafka (enables cross-instance fanout)
3. Transactional outbox (guarantees message delivery)
4. Consuming from Kafka (receives messages from other instances)
5. Testing it end-to-end (validates the entire flow)

Once Phase 1 is complete, the application will correctly distribute messages across all instances. Phase 2 adds production resilience, Phase 3 adds confidence through testing, and Phase 4 adds deployment readiness.

### Design Decisions Requiring Clarity

Before starting Phase 2, clarify these design choices:

1. **Session Failover Strategy:** Active-active (stateless reconnect) or active-passive (session migration)?
2. **Message Ordering:** Per-channel strict ordering or eventual consistency with causal ordering?
3. **Kafka Topology:** Single topic with channel_id partitioning, or topic-per-channel?
4. **Redis vs. Database for Session State:** Redis for speed or database for durability?
5. **Graceful Shutdown:** Drain connections (wait for idle) or force close with reconnect?

## Docker Compose Services

When running `./gradlew dockerComposeUp`, the following services start:
- `keycloak-db`: PostgreSQL for Keycloak
- `keycloak`: Identity provider (port from env: KC_HOST_PORT)
- `citus_master`: Citus coordinator (port from env: MASTER_PORT)
- `citus_worker_1/2/3`: Citus worker nodes
- `messaging_app`: Micronaut application instances (scaled to 3 replicas)
- `envoy`: Load balancer and auth gateway (port from env: ENVOY_PORT)