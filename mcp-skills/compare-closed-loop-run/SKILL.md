---
name: compare-closed-loop-run
description: Validate a Consilens config, submit a diff run, and diagnose the run result when available.
---

# Compare Closed Loop Run

Use this skill when the user has a config artifact or inline config and wants the standard validate -> run -> diagnose loop.

## Inputs

- `configArtifactId` or `configContent`.
- `serialNo`: caller-provided idempotency key for `consilens.run.diff`.
- `runOptions`: optional `dryRun` and `timeoutMs`.
- `diagnoseOptions`: optional diagnosis controls.

## Workflow

1. Call `consilens.validate.config` with exactly one of `configArtifactId` or `configContent`.
2. If validation succeeds, call `consilens.run.diff` with `serialNo`, `configArtifactId`, and `options`. `configArtifactId` must reference a CONFIG artifact (from `consilens.plan.config` or the caller), not the VALIDATION_RESULT artifact returned by step 1.
3. Wait for or obtain the RUN_RESULT artifact through the caller's task tracking flow.
4. Call `consilens.diagnose.diff` with `runArtifactId`.
5. Return task id, run artifact id, diagnosis artifact id, and next actions.

## Failure Policy

- Missing `serialNo` is a caller error; do not generate one silently.
- If validation fails, stop before submitting the run.
- If run submission fails, stop and return the structured error.
- If diagnosis fails, return the run result and diagnosis error together.

## Output

Return structured JSON containing:

- `validationArtifactId`
- `taskId`
- `runArtifactId`
- `diagnosisArtifactId`
- `availableNextActions`
