# Builder Incident — Typed Evidence Fixture Mismatch

- Incident ID: BUILDER-INCIDENT-EVIDENCE-REF-TEST-FIX-001
- Date: 2026-11-05
- Scope: PR #35, correction lifecycle evidence-reference validation
- Status: OPEN_RECHECK
- Severity: CI regression; no production or device impact

## Observed behavior

Android JVM workflow run 42107786793 failed at `:app:testDebugUnitTest`. Java production and test compilation succeeded. The suite reported 127 tests completed with one failure:

- `BuilderConversationCoordinatorTest.coordinatesCorrectionPlanThroughVerifiedAndUserAdoptedMilestones`
- Assertion location: `BuilderConversationCoordinatorTest.java:246`

## Expected behavior

Coordinator test fixtures should use the same typed evidence-reference contract enforced by `BuilderCorrectionLedger`:

- IMPLEMENTED: full-SHA GitHub commit reference with branch.
- TESTED / VERIFIED: GitHub Actions run reference with workflow and tested SHA.

## Cause

The new ledger validation correctly rejected the coordinator test's legacy placeholder strings (`artifact-implemented`, `artifact-tested`, and `artifact-verified`). The test fixture had not been updated when the ledger began requiring typed references. This was a test-fixture integration omission, not a Java compilation failure.

## Correction made

Commit `be6f7b4871a8f1f49983d2cb17c0fe0791a45219` updates the coordinator test fixture to use typed commit and Actions references. The original failed run is retained as evidence; it was not rerun or overwritten.

## Recheck

- Latest branch head at incident update: `be6f7b4871a8f1f49983d2cb17c0fe0791a45219`.
- Android reruns 42107976865 and 42107966566 were observed in progress at the time of recording; neither is yet accepted as a passing result.
- Lean build and analysis-engine run 42107977124 completed successfully on the latest head.
- The incident remains OPEN_RECHECK until a successful Android run is confirmed against the latest head.

## Limits

No live GitHub evidence gateway is connected. The resolver's fake-gateway tests are not live integration evidence. No deployment, credential change, device modification, or merge occurred.
