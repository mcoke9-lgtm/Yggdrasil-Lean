# Adaptive Filesystem — Autonomous Batch Mode 001

Status: proposed operating policy; applied to the current work session by user instruction on 2026-10-27.

## Operating rule
Proceed through the active branch's safe, dependency-resolved work without asking the user to confirm each build step. The user should not be told what the next build step is after every build. Deliver a consolidated report at meaningful milestones, when blocked on a decision/permission, or when a material risk or scope change arises.

## Batch loop
1. Resolve the active project and branch from durable project records before acting.
2. Inspect current repository state and latest CI for the exact commit.
3. Select the next highest-value task already implied by the active plan; do not branch the project merely because a new chat began.
4. Implement a coherent batch of changes, including tests and documentation.
5. Run the narrowest relevant checks, then broader tests/builds where available.
6. Inspect failures; fix and rerun when safe. Do not claim success without result evidence.
7. Update this checkpoint and the project `p` checkpoint with commits, test statuses, decisions, and unresolved limits.
8. Continue to the next safe task without a user-facing progress message at each substep.

## Stop and ask only when
- An irreversible or externally consequential action requires explicit authorization.
- A material product/design decision cannot be inferred from existing requirements.
- Required credentials, access, or user input are genuinely unavailable.
- Continuing would risk data loss, security, privacy, or misleading evidence.

## Workarounds when blocked
Continue independent work: unit/regression tests, offline fixtures, source review, documentation, architectural audits, error-path coverage, and deterministic reproducibility. Label mocked/fixture checks separately from live integration. Never mutate the Airtable pilot unless explicitly authorized.

## Reporting contract
At milestones, report:
- scope and branch;
- completed changes with commit links;
- exact tests/builds and outcomes;
- durable checkpoint/audit links;
- remaining blockers and uncertainty;
- next work only when useful for explaining a dependency or decision, not as routine step-by-step narration.

## Integrity requirements
Keep proposed / implemented / tested / verified / adopted statuses distinct. Preserve source identity and evidence boundaries. Do not claim a live connector call occurred in CI unless it did. A checkpoint commit is not proof that all tests for that commit passed.
