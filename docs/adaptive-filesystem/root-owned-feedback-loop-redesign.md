# Adaptive Filesystem — Root-Owned Two-Way Feedback Loop Redesign

Status: proposed design; not production-integrated or runtime-verified.
Baseline: `af-recovery-roundtrip-harness-v0`, commit `ae2a5c5123df35b6e850e06611359b8b44f9b1ba`.
This document deliberately does not alter the passing baseline.

## Correction

The previous allocation made roots preserve state while assigning report generation to AIRA-of-TERA / leaf-like parts. That allocation is rejected.

TERA roots own both:
1. **Preservation** — retain canonical source state, provenance, uncertainty, and change history.
2. **Generation** — produce a post-connection report from the preserved state and observed connection result.

AIRA/leaves own output and recovery representation, not ownership of the post-connection report. FIRA coordinates the connection and routes the report through the feedback loop. WATA explores options; it does not silently mutate preserved state.

## Revised two-way feedback loop

1. **Capture:** TERA records the source state, provenance, unresolved claims, and `changes_intended`.
2. **Connect:** FIRA records the connection event and the identities of both endpoints.
3. **Preserve:** TERA snapshots the resulting evidence without turning planned changes into executed changes.
4. **Generate:** TERA generates the post-connection report from that preserved state plus the connection event.
5. **Return:** FIRA routes the report to the originating context/reader and records that it was returned or marks delivery unknown.
6. **Feedback:** The reader or originating context returns corrections, acceptance, questions, or a proposed next action. TERA preserves that feedback with provenance and produces an updated report on the next cycle. No change is applied merely because it appears in feedback.

The two directions are therefore:
- **Outbound:** source/context → FIRA connection → TERA preservation + report generation → returned report.
- **Return:** reader/context → feedback → FIRA routing → TERA preservation + revised report.

## Post-connection report contract

The generated report must explicitly include:
- `connection_event`: identifier, endpoints, status, and evidence of completion;
- `preserved_state`: source records and relationships with provenance;
- `leaf_parts`: output/recovery references, not report ownership;
- `relationship_evidence`: relationship identity, status, and provenance;
- `changes_intended`: planned changes, still distinct from actual changes;
- `observed_changes`: only changes supported by evidence;
- `unknowns`: unresolved or unavailable information, never silently upgraded to confirmed;
- `feedback_next_action`: an explicit review/return action or an explicit no-action reason.

A report is incomplete if the connection happened but no post-connection report was generated, or if report generation is claimed without evidence of the generated artifact.

## Why the schema did not prompt the missing component

A schema is a declarative shape/constraint, not an execution plan. A field can be present or required in a data object without causing the system to perform the operation that fills it. The earlier design also conflated:
- schema definition with operational behavior;
- required checkpoint data with a generated post-connection artifact;
- planned work with executed and verified work.

The exact historical implementation cause remains unknown. The current GitHub harness explicitly labels itself synthetic and not the production Adaptive Filesystem runtime; therefore it cannot establish that a production schema or runtime implements this loop. The next integration step must locate an actual production connection path and attach report generation there, with a test that fails when a connection completes without a report.

## Minimum acceptance tests for the redesigned loop

1. A completed connection yields a TERA-generated post-connection report.
2. The report is derived from the preserved TERA state and the specific connection event.
3. Provenance loss remains visible in the report.
4. Planned changes remain planned unless execution evidence exists.
5. Missing connection or source evidence produces `UNKNOWN`/incomplete, never `PASS`.
6. Returned feedback is preserved and routed back to the originating context.
7. A report-generation failure is an explicit failure; it cannot be hidden by a valid schema or a successful connection.
8. Re-running the loop preserves history and does not duplicate or silently overwrite the prior report.

## Status boundary

- **Proposed:** root-owned preservation + generation; two-way feedback loop; report contract and acceptance tests.
- **Verified:** the existing synthetic recovery harness passed its eight tests at run `40851559979` on commit `ae2a5c5123df35b6e850e06611359b8b44f9b1ba`.
- **Not verified:** this redesign's tests, production integration, real report persistence, return-feedback routing, or an automatic schema-completeness guard.
- **Not inferred:** the precise historical reason the missing report-generation component was not created.
