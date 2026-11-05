---
name: compare-config-build
description: Build and validate a Consilens compare config from an explicit compare goal, source/target endpoints, keys, and optional hints.
---

# Compare Config Build

Use this skill when the user wants a new Consilens compare config artifact, not when they already have a validated config.

## Inputs

- `goal`: concise compare intent.
- `source`: endpoint object with `type` and either `table` or `query`.
- `target`: endpoint object with `type` and either `table` or `query`.
- `keys`: non-empty primary key fields.
- `hints`: optional planning hints such as fields, strategy, checksum algorithm, filters, or sampling.

## Workflow

1. Call `consilens.plan.config` with `goal`, `source`, `target`, `keys`, and `hints`.
2. Read the returned CONFIG artifact id.
3. Call `consilens.validate.config` with `configArtifactId`.
4. Return the CONFIG artifact id and validation artifact id.

## Failure Policy

- If planning fails, stop and surface the structured tool error.
- If validation fails, stop and return the validation artifact or error payload.
- Do not invent missing endpoints, credentials, keys, or table names.

## Output

Return structured JSON containing:

- `configArtifactId`
- `validationArtifactId`
- `availableNextActions`
