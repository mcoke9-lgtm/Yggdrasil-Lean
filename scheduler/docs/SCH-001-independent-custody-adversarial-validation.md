# SCH-001 Independent Custody Gate — Adversarial Validation Set

Status: PROPOSED TESTS; conceptual outcomes evaluated against the gate model.
Model under test: scheduler/docs/SCH-001-independent-custody-gate-model.md
Decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE

## Purpose

Test whether the ten non-compensatory custody gates reject plausible common-mode failures, recovery bypasses, and misleading evidence. These are design-level adversarial cases, not live penetration tests or provider attestations.

## Test cases

| ID | Adversarial condition | Required model response | Rationale |
|---|---|---|---|
| CG-01 | Scheduler host administrator can also obtain witness write credentials from CI secrets. | FAIL G1/G3; candidate not qualified. | Separate products do not create separate control domains when one operator can write both. |
| CG-02 | Witness has a separate administrator, but the same identity-provider recovery email/phone can reset both accounts. | FAIL or UNKNOWN G2 until independently controlled recovery is evidenced. | Account labels and nominal role separation do not survive a shared recovery channel. |
| CG-03 | Witness database supports conditional writes, but a zero-row guard result does not abort the surrounding transaction. | FAIL G5. | A transaction that commits despite failed compare-and-swap can accept competing authority. |
| CG-04 | Witness database and its only audit log are restored together from the same old backup. | FAIL G4 unless a separately controlled monotonic continuity receipt detects rollback. | Internal consistency after restore does not prove freshness. |
| CG-05 | Receipt is cryptographically signed, but the scheduler administrator controls the signing key. | FAIL G3/G6. | Signature validity proves possession of a key, not independent custody. |
| CG-06 | Independent reviewer has read-only credentials, but all evidence is exported through a scheduler-controlled dashboard that can omit events. | FAIL or UNKNOWN G7 until direct evidence access and completeness are demonstrated. | Read-only access to a filtered view is not independent observation. |
| CG-07 | Witness service is unavailable; scheduler proposes to continue using its last cached generation. | FAIL G9/G8; stop authority changes and protected side effects. | Availability failure must not silently weaken the authority boundary. |
| CG-08 | Source is fenced at task start, but an already-running handler can perform an external side effect after losing authority. | FAIL G8. | Fencing must be checked at the actual side-effect boundary, not only at activation. |
| CG-09 | Cloud organization super-admin can delete the witness, alter recovery settings, and remove its audit trail. | FAIL G1/G2/G4 unless an independent custodian retains continuity evidence and can detect/recover. | Service-account separation is insufficient against common organization-level authority. |
| CG-10 | A witness migration copies current records but resets sequence numbers or drops prior receipts. | FAIL G4/G10. | Migration must preserve identity, monotonic sequence and verifiable lineage. |
| CG-11 | Lost append response causes scheduler to retry with a new transfer ID. | FAIL G5/G6 operational protocol; quarantine until original ID/outcome is reconciled. | A new ID can create a second transfer when the first append actually committed. |
| CG-12 | A human custodian holds an offline signed checkpoint but cannot demonstrate when or how it was created, or whether it was replaced. | UNKNOWN G4/G7; not qualified. | Offline storage alone does not establish authenticity, freshness, or independent custody. |
| CG-13 | One billing or subscription owner can suspend, close, transfer, or regain control of both source and witness accounts. | FAIL G1/G2 unless a separately controlled continuity custodian preserves and verifies authority. | Administrative separation can collapse through commercial ownership and account lifecycle control. |
| CG-14 | A single operator can rotate the witness signing key, replace the custodian, or reset the trust baseline without independent approval and preserved lineage. | FAIL G3/G6/G10; quarantine key transition. | Key transition and baseline reset are authority changes, not routine maintenance. |
| CG-15 | A valid receipt is replayed or substituted with a different task identity, prior hash, transfer ID, or witness identity. | FAIL G6; reject and quarantine the transfer. | Signature validity alone is insufficient unless all authority-binding fields and replay identity are verified. |
| CG-16 | A reviewer credential is compromised or mis-scoped and can write, delete, suppress, or alter witness evidence. | FAIL G7/G1/G3; revoke review eligibility and quarantine affected evidence. | A nominally independent reviewer role must be demonstrably read-only and separately controlled.

## Evaluation results

- Cases defined: 12.
- Cases with required fail-closed or unknown outcome: 12.
- Cases that may be approved by a weighted average: 0.
- Live provider tests performed: 0.
- Independent reviewer validation: 0.
- Production controls verified by this document: 0.

## Model consistency findings

1. The hard-gate rule correctly rejects a candidate when any critical gate fails or remains unknown.
2. A separate product, account, database, key, or reviewer label is not accepted as proof of independence without effective-control evidence.
3. A successful local hash-chain replay is not accepted as proof of freshness after coordinated rollback.
4. A valid signature is not accepted as proof of independent key custody.
5. A durable receipt is not accepted as proof of destination activation or fencing.
6. A cached lease or generation is not accepted as permission to continue protected side effects after witness loss.
7. An uncertain append outcome requires same-transfer-ID reconciliation; no blind new-ID retry.
8. Restore and migration must preserve externally anchored continuity; otherwise the system remains quarantined.\n9. Billing/account lifecycle authority, key/baseline succession, complete receipt binding, and reviewer write-scope are explicit adversarial dimensions.

## Clarifications added to the test interpretation

- “Separate administrator” means no shared principal or higher-level authority can silently control both source and witness, including identity recovery and organization super-admin paths.
- “Independent reviewer” means direct, separately authenticated access to sufficiently complete evidence, not merely a report generated by the source system.
- “Fencing” must be enforced immediately before each protected external side effect, or by an external destination that rejects stale generations atomically.
- “Unknown” is a blocking result. It is not equivalent to absence, but it cannot satisfy an adoption gate.

## Current decision

WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

This validation set strengthens the proposed assessment model. It does not qualify GitHub, Airtable, Dropbox, Cloudflare D1, or any other service. No account settings, credentials, permissions, infrastructure, task authority, or deployment state were changed.
