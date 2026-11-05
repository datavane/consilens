---
name: aggregate-compare-build
description: Build and validate multiple Consilens compare config artifacts for an aggregate or batch comparison.
---

# Aggregate Compare Build

Use this skill when the user needs several related compare configs generated from one aggregate compare request.

## Inputs

- `goal`: aggregate compare intent.
- `targets`: list of compare targets, each with `source`, `target`, `keys`, and optional `hints`.
- `sharedHints`: optional hints that apply to every target unless overridden.

## Workflow

1. For each target, merge `sharedHints` with the target-specific `hints`.
2. Call `consilens.plan.config` with the target's `goal`, `source`, `target`, `keys`, and merged hints.
3. Call `consilens.validate.config` with the returned `configArtifactId`.
4. Return one result item per target.

## Failure Policy

- Do not let one target's failure hide other target results.
- Return failed targets with the tool error payload and successful targets with artifact ids.
- Do not invent missing source/target endpoints or keys.

## Output

Return structured JSON containing:

- `items`: each item has target id/name, `configArtifactId`, `validationArtifactId`, or `error`.
- `failedCount`
- `succeededCount`
