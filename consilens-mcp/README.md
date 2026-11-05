# Consilens MCP

`consilens-mcp` is a standalone MCP server for Consilens. It exposes Consilens server HTTP
capabilities as MCP tools and resources, without embedding Agent or protocol code in
`consilens-server`.

## Build

This module requires Java 17 because the official MCP Java SDK targets Java 17.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -Pconsilens-mcp -pl consilens-mcp test
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -Pconsilens-mcp -pl consilens-mcp package
```

## Stdio

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/java" -jar consilens-mcp/target/consilens-mcp-0.1-SNAPSHOT.jar \
  --transport stdio --server-url http://127.0.0.1:8080
```

## Streamable HTTP

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17)
"$JAVA_HOME/bin/java" -jar consilens-mcp/target/consilens-mcp-0.1-SNAPSHOT.jar \
  --transport http \
  --http-host 127.0.0.1 \
  --http-port 8090 \
  --mcp-endpoint /mcp \
  --server-url http://127.0.0.1:8080 \
  --connect-timeout-ms 10000 \
  --request-timeout-ms 60000 \
  --allowed-hosts "localhost:8090,127.0.0.1:8090" \
  --allowed-origins "http://localhost:*,http://127.0.0.1:*"
```

HTTP mode listens on `127.0.0.1` by default and exposes `GET /healthz` for load
balancers and container probes. Set `--http-host 0.0.0.0` only when the process
must accept external connections directly.
By default, HTTP transport accepts only localhost hosts and origins:
`localhost:*`, `127.0.0.1:*`, `[::1]:*`, `http://localhost:*`,
`http://127.0.0.1:*`, and `http://[::1]:*`. Set explicit host and origin allowlists
when exposing MCP through a gateway.

Configuration can also be provided through:

- `CONSILENS_SERVER_URL`
- `CONSILENS_SERVER_API_KEY`
- `CONSILENS_MCP_HOST`
- `CONSILENS_MCP_PORT`
- `CONSILENS_MCP_ENDPOINT`
- `CONSILENS_MCP_CONNECT_TIMEOUT_MS`
- `CONSILENS_MCP_REQUEST_TIMEOUT_MS`
- `CONSILENS_MCP_ALLOWED_HOSTS`
- `CONSILENS_MCP_ALLOWED_ORIGINS`

`CONSILENS_SERVER_URL` may include a gateway prefix, such as
`https://gateway.example.com/consilens/`. MCP tool requests preserve that prefix when calling
the Consilens HTTP API.
MCP forwards a generated `X-Trace-Id` on every Consilens server request and returns the
trace id in tool error payloads when the server or gateway provides one.

## Tools and Resources

The MCP tool catalog maps to the Consilens server API:

- `consilens.plan.config` -> `POST /v1/plan`
- `consilens.validate.config` -> `POST /v1/validate`
- `consilens.run.diff` -> `POST /v1/tasks/execute`, with caller-provided `serialNo`
- `consilens.diagnose.diff` -> `POST /v1/diagnose`
- `consilens.repair.config` -> `POST /v1/repair`
- `consilens.get.artifact` -> `GET /v1/artifacts/{artifactId}`

Resource ids in `consilens://configs/{configId}`,
`consilens://artifacts/{artifactId}`, and `consilens://diagnosis/{diagnosisId}` must be
URI encoded when they contain spaces or reserved characters.
