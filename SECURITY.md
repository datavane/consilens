# Security Policy

## Supported versions

Security fixes are applied to the latest released minor version. The `main`
branch receives fixes before a release is cut.

## Reporting a vulnerability

Please do not open a public issue for security-sensitive reports. Use GitHub's
private vulnerability reporting on the repository's **Security** tab
("Report a vulnerability"), including:

- affected version/commit;
- a minimal reproduction (config, data source type, command);
- expected and actual behavior;
- any evidence of credential exposure, data leakage or remote code execution.

We aim to acknowledge reports within 3 business days and provide a remediation
plan within 10 business days. Please allow time for a fix and coordinated
disclosure before publishing details.

## Scope

In scope:

- credential leakage in logs, artifacts or error messages;
- unintended data exfiltration through connectors, S3 or report sinks;
- path traversal in file/S3 resources;
- unsafe deserialization or command injection in CLI/MCP entry points;
- dependency vulnerabilities with a reachable exploit path.

Out of scope:

- vulnerabilities requiring a compromised database or S3 account;
- denial of service through intentionally huge comparison inputs;
- issues in third-party databases/drivers that are not reachable through
  Consilens configuration.

## Handling credentials

- Connection passwords and S3 secret keys must not be committed to configs.
- Prefer environment variables or cloud IAM roles.
- Consilens masks JDBC URLs in error messages, but configuration files remain
  the operator's responsibility.
