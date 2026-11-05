---
name: compare-diagnose-repair
description: Diagnose a Consilens run result and generate a repair config artifact.
---

# Compare Diagnose Repair

Use this skill when the user has a RUN_RESULT artifact and wants an executable repair config.

## Inputs

- `runArtifactId`: artifact id from a completed diff run.
- `diagnoseOptions`: optional diagnosis controls.
- `repairOptions`: optional repair controls such as `requireApproval`.

## Workflow

1. Call `consilens.diagnose.diff` with `runArtifactId` and optional `diagnoseOptions`.
2. Read the returned DIAGNOSIS artifact id.
3. Call `consilens.repair.config` with `diagnosisArtifactId` and optional `repairOptions`.
4. Return diagnosis and repair config artifact ids.

## Failure Policy

- If diagnosis fails, stop and return the diagnosis error payload.
- If repair generation fails, return the diagnosis artifact id and repair error payload.
- Do not execute repair output; this skill only creates a repair config artifact.

## Output

Return structured JSON containing:

- `diagnosisArtifactId`
- `repairConfigArtifactId`
- `availableNextActions`
