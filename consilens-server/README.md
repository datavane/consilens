# Consilens Server

`consilens-server` is the Spring Boot HTTP service for Consilens atomic compare
capabilities. MCP and Agent skills should call this API instead of embedding server
logic.

## Build

```bash
./mvnw -pl consilens-server -am test
./mvnw -pl consilens-server -am package
```

## Production Database

`consilens-server` now defaults to the `local` Spring profile so that
`./mvnw -pl consilens-server spring-boot:run` starts against an in-memory H2 database,
auto-initializes the schema, and disables API-key enforcement for local smoke tests.

Production deployments must switch to the `prod` profile, configure a persistent
datasource, and initialize the schema before starting the service.

MySQL bootstrap:

```bash
mysql -h <host> -u <user> -p <database> < consilens-server/src/main/resources/schema-mysql.sql
```

Runtime configuration:

```bash
java -jar consilens-server/target/consilens-server-0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.datasource.url=jdbc:mysql://<host>:3306/<database>?useSSL=false&serverTimezone=UTC \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --consilens.server.node.node-key=<stable-node-key> \
  --consilens.server.security.api-key=<secret> \
  --consilens.server.artifact.local-base-dir=/var/lib/consilens/artifacts \
  --consilens.server.artifact.max-content-bytes=52428800 \
  --consilens.server.api.max-request-body-bytes=10485760
```

The API key is required when `consilens.server.security.enabled=true` and must be sent
as `X-Consilens-Api-Key` on `/v1/*` requests. `GET /actuator/health` and its
`liveness`/`readiness` groups stay open for health checks.

For local smoke tests:

```bash
./mvnw -pl consilens-server spring-boot:run
```

## Create Task

`POST /v1/tasks/execute` creates an asynchronous run task and returns its task ID.
The task configuration is referenced by `configArtifactId`, or passed inline as a
`configContent` string (YAML or JSON) when no artifact is available.

```bash
curl -X POST http://localhost:16789/v1/tasks/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "serialNo": "orders-compare-20260808",
    "configArtifactId": "artifact-config",
    "options": {
      "dryRun": false
    }
  }'
```

The response has HTTP status `202 Accepted` and includes
`data.taskId`. Query progress and results with `GET /v1/tasks/{taskId}`.

Callers holding a configuration file can pass its content directly as a string:

```bash
curl -X POST http://localhost:16789/v1/tasks/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "serialNo": "orders-compare-20260808",
    "configContent": "source:\n  type: mysql\n  name: source-mysql\n  connection:\n    url: jdbc:mysql://127.0.0.1:13306/consilens_demo\n    username: demo\n    password: demo\n  resource:\n    type: table\n    name: orders\ntarget:\n  type: postgresql\n  name: target-postgresql\n  connection:\n    url: jdbc:postgresql://127.0.0.1:5432/consilens_demo\n    username: demo\n    password: demo\n  resource:\n    type: table\n    name: orders\ncomparison:\n  keys:\n    source: [id]\n    target: [id]\n  fields:\n    source: [name, amount]\n    target: [name, amount]",
    "options": {
      "dryRun": false
    }
  }'
```

Inline config content supports `server-native` JSON, the CLI configuration file
format (YAML or JSON), and `${env.NAME}` environment placeholders resolved from
the server process environment. File sources are supported too: use `type: file`
with `resource: {type: file, name: <path>, format: csv}`; no `connection` block
is required.

## Operations

- HTTP port defaults to `16789`.
- Actuator exposes `/actuator/health`, `/actuator/health/liveness`,
  `/actuator/health/readiness`, and `/actuator/info` (build artifact/version); the readiness group includes
  the database health indicator, so pods stop receiving traffic while the database is
  unavailable, while liveness only reflects the application state. Both are suitable
  for Kubernetes `livenessProbe`/`readinessProbe`.
- The server shuts down gracefully on `SIGTERM` with a 30-second in-flight request
  budget (`server.shutdown=graceful`).
- Every response carries `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: no-referrer`, and a
  restrictive `Permissions-Policy`.
- `/v1/*` responses are marked `Cache-Control: no-store` so comparison payloads
  are not retained by shared proxies or browser caches.
- `X-Trace-Id` is propagated in API responses and should be passed by gateways, MCP,
  and automation clients.
- `consilens.server.node.node-key` can pin a stable node identity in containers or
  NATed deployments.
- Local artifact storage defaults to `./.consilens-server/artifacts`; use a persistent
  path in production.
- Artifact content is capped by `consilens.server.artifact.max-content-bytes`, default
  `52428800` bytes.
- `/v1/*` request bodies are capped by `consilens.server.api.max-request-body-bytes`,
  default `10485760` bytes.
