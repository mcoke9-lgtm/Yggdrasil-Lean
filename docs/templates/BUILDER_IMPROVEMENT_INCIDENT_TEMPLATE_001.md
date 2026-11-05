# Builder Improvement Incident Record

> Copy this template for each failure, near miss, or materially unsuitable method. Preserve the original record; append corrections rather than rewriting history.

## A. Identity and scope

- Incident ID:
- Title:
- Date/time observed (include timezone):
- Reporting Builder/person:
- Affected Builder(s):
- Project / branch / PR / commit / artifact:
- Related incident(s):
- Severity / impact:
- Status: `OPEN` / `DIAGNOSING` / `CORRECTION_PROPOSED` / `IN_REVIEW` / `TESTING` / `VERIFIED` / `ADOPTED` / `DEFERRED` / `REJECTED` / `REVERTED`

## B. Observation (facts before interpretation)

- Exact user request or triggering condition:
- Exact Builder response/action (quote or link):
- Expected behavior:
- Actual behavior:
- Context available to the Builder at the time:
- Evidence/source links:
- Evidence timestamps and freshness:
- Directly observed facts:
- Inferences (label them):
- Unknowns:
- Immediate containment or interruption:
- Work paused / stopped / safe to resume? Why?

## C. Diagnosis

Mark all plausible causes and explain each:
- [ ] Missing or incorrect knowledge
- [ ] Stale, contradictory, or weak evidence/provenance
- [ ] Reasoning or interpretation error
- [ ] Shared context not delivered, retrieved, or retained
- [ ] Tool, runtime, connector, scheduler, or permission unavailable
- [ ] Unsuitable method not challenged or interrupted
- [ ] Ownership, coordination, or handoff failure
- [ ] Authorization-boundary confusion
- [ ] Other:

For each cause:
- Supporting evidence:
- Contradicting evidence:
- Confidence:
- What would distinguish it from other causes?

Root cause conclusion (or state why diagnosis remains open):

## D. Proposed correction

- Smallest adequate change:
- Artifact/procedure/knowledge/routing to change:
- Accountable owner:
- Contributors:
- Dependencies:
- Permissions/authorization required:
- Expected behavior after correction:
- Acceptance criteria:
- Side effects / risks:
- Rollback or recovery plan:

## E. Peer challenge

- Reviewers (must be independent of the correction author):
- Huginn evidence/provenance review:
- Höðr assumption/method challenge:
- Mari alternative-method comparison:
- Kvasir constraint interpretation:
- Loki interruption/recovery review:
- Muninn continuity/history review:
- Týr authorization review:
- Odin coordination decision:
- Baldr independent assessment:
- Dissenting views and evidence:
- Review outcome: `ACCEPT` / `REVISE` / `REJECT` / `MORE_EVIDENCE_REQUIRED`

Only fill role reviews relevant to the case; do not imply a review occurred when it did not.

## F. Test plan and results

For each test, record:
- Test ID and purpose:
- Preconditions / environment:
- Artifact version or commit:
- Exact procedure/command:
- Expected result:
- Actual result:
- Pass/fail/not run:
- Evidence (logs, CI run, screenshot, source link):
- Reviewer:

Required coverage:
- [ ] Original triggering case
- [ ] Normal/available case
- [ ] Blocked or unavailable dependency
- [ ] Stale/missing/unknown/contradictory evidence
- [ ] Boundary and recovery behavior
- [ ] Authorization-restricted case
- [ ] Regression / unrelated behavior

## G. Decision and adoption

- Implementation status: `PROPOSED` / `IMPLEMENTED`
- Test status: `NOT_RUN` / `TESTED_PASS` / `TESTED_FAIL`
- Verification status: `UNVERIFIED` / `VERIFIED` (scope and evidence):
- Adoption status: `NOT_ADOPTED` / `ADOPTED` / `DEFERRED` / `REJECTED` / `REVERTED`
- Decision owner and date:
- Decision rationale:
- Remaining limitations / residual risks:
- Follow-up owner:
- Reassessment trigger/date:

## H. Integrity and continuity check

- [ ] Exact triggering behavior preserved
- [ ] Facts, inferences, and unknowns separated
- [ ] System-level causes considered before individual attribution
- [ ] All relevant causes and dependencies considered
- [ ] Correction has an owner, acceptance criteria, and rollback path
- [ ] Independent review actually completed and recorded
- [ ] Test plan distinguished from tests actually run
- [ ] Evidence matches the exact artifact/version tested
- [ ] Proposed / implemented / tested / verified / adopted states kept distinct
- [ ] Authorization boundaries preserved
- [ ] Prior history retained; no silent overwrite
- [ ] Reassessment trigger assigned

## I. Append-only change log

| Date/time | Author/Builder | Change or new evidence | Reason | Evidence link |
|---|---|---|---|---|
| | | | | |
