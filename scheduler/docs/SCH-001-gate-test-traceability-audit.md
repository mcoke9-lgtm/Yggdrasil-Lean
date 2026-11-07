# SCH-001 Gate-to-Test Traceability and Coverage Audit

Status: PROPOSED
Audit type: Conceptual consistency and coverage review
Decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE
Inputs:
- SCH-001-independent-custody-gate-model.md (10 hard gates; blob 94118bfb80f88b80e4320dcfb247e5a8922ef8ba)
- SCH-001-independent-custody-adversarial-validation.md (16 conceptual cases; revised commit a5afdcd10eb830530a5783003d0bfba290046526)
- SCH-001-existing-service-evidence-protocol.md (10 evidence bundle items)

## 1. Scope and limits

This audit checks whether each hard gate has at least one explicit adversarial case and a corresponding evidence-acquisition path. It also checks whether important shared-control risks appear as explicit tests. It does not execute provider tests, inspect new account settings, validate a live deployment, or establish independent reviewer approval.

## 2. Gate traceability matrix

| Gate | Required control | Adversarial coverage | Evidence bundle | Coverage judgement |
|---|---|---|---|---|
| G1 Administrative separation | No shared unrestricted administrator or higher-level authority. | CG-01, CG-09, CG-13, CG-16 | E1, E2, E4 | Covered; common admin, super-admin, billing lifecycle, reviewer scope represented. |
| G2 Recovery separation | Recovery authority cannot silently control source and witness together. | CG-02, CG-04, CG-09, CG-13 | E1, E2, E3, E6 | Covered; shared identity recovery, restore, super-admin, account lifecycle represented. |
| G3 Credential separation | Source/CI operators cannot obtain witness write, signing, or recovery credentials. | CG-01, CG-05, CG-14, CG-16 | E4, E10 | Covered; CI credential reach, signing custody, unilateral transition and reviewer scope represented. |
| G4 Monotonic continuity | Restore, deletion, rollback, or migration cannot silently revive stale authority. | CG-04, CG-09, CG-10, CG-12, CG-13 | E6 | Covered; coordinated restore, deletion, migration, unverifiable offline checkpoint and account lifecycle represented. |
| G5 Conditional append | Competing writers cannot both commit; failed guard aborts transaction. | CG-03, CG-11 | E5 | Covered; failed zero-row guard and uncertain append/new-ID retry represented. |
| G6 Receipt authenticity | Receipt binds stable ID, task, prior/new sequence and hash, and witness identity; substitution is detectable. | CG-05, CG-11, CG-12, CG-14, CG-15 | E10 (plus E5/E6 test evidence) | Covered after revision; key custody, retry identity, checkpoint provenance, key transition and field substitution represented. |
| G7 Independent observation | Reviewer has separate credentials and sufficiently complete direct evidence. | CG-06, CG-12, CG-16 | E7 | Covered after revision; filtered source-controlled view, unverifiable checkpoint and reviewer write-scope represented. |
| G8 Side-effect fencing | Every protected side effect rejects stale authority at the actual boundary. | CG-07, CG-08 | E8 | Covered; witness outage/cache fallback and already-running handler side effect represented. |
| G9 Failure-safe behavior | Outage, stale state, contradiction, uncertain append, or missing reviewer evidence blocks. | CG-06, CG-07, CG-11, CG-12, CG-15, CG-16 | E9 | Covered; failure cases span outage, uncertainty, evidence gaps and compromised reviewer. |
| G10 Recovery and key transition | Restore, key rotation, migration, baseline reset and succession require dual control and preserve continuity. | CG-04, CG-10, CG-12, CG-13, CG-14 | E3, E6, E10 | Covered after revision; dual-control/key/baseline succession now explicit. |

## 3. Adversarial cases added

The first 12 cases did not explicitly exercise four dimensions already identified in the gate model's shared-control collision tests or G10:
- CG-13: billing/account lifecycle authority common to source and witness.
- CG-14: unilateral signing-key rotation, custodian replacement, or trust-baseline reset.
- CG-15: replay/substitution of a valid receipt with mismatched authority-binding fields.
- CG-16: reviewer credential that is nominally read-only but can alter or suppress evidence.

These were added to the adversarial validation set. The set now contains 16 conceptual cases. Each has a blocking outcome.

## 4. Remaining quality observations

1. Gate coverage is now explicit, but test coverage is not test execution. All 16 cases remain conceptual; live provider tests performed remain zero.
2. E10 currently maps to G3, G6 and G10. Its evidence bundle should be read alongside E4 (identity/credential separation), E6 (continuity/restore), and E7 (reviewer access) rather than treated as a standalone proof.
3. The model's G5 requires a live test on the actual candidate service. Provider documentation and a local/workerd compatibility test cannot satisfy this requirement.
4. G8 requires destination-specific enforcement tests at every protected side-effect boundary; scheduler-side checks alone do not pass.
5. Independence cannot be proven solely by account labels or owner attestations. A reviewer must be able to reproduce key observations from a separately authenticated domain.
6. Shared-control collision tests in the gate model remain a distinct checklist. Their coverage by one or more CG cases does not mean the collision has been ruled out in any candidate.

## 5. Integrity check

- Hard gates in model: 10.
- Gates with at least one explicit adversarial case: 10.
- Evidence bundle items: 10.
- Gates with at least one mapped evidence item: 10.
- Adversarial cases in revised set: 16.
- Cases with blocking outcome: 16.
- Live provider tests performed by this audit: 0.
- Independent reviewer validation: 0.
- New services/resources provisioned: 0.
- Account settings, credentials, permissions, deployments, or task authority changed: 0.

## 6. Current disposition

The conceptual traceability gap identified in the first 12-case set is closed by four additional cases. This is a documentation-level coverage result only. It is not a security certification, provider test, independent review, or adoption approval.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.
