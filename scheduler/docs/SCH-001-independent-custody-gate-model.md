# SCH-001 Independent Custody Gate — Proposed Assessment Model

Status: PROPOSED
Decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE
Scope: Evaluate an existing service as a possible independent scheduler-authority witness. This is a design and evidence-classification artifact, not a security audit or adoption decision.

## 1. Governing rule

Independence is a set of non-compensatory gates, not an average score. Strong encryption, availability, durability, or audit logging cannot offset shared administrative control, shared recovery control, or an unproven ability to reject stale authority.

A candidate is not independent merely because it is a different product, database, cloud region, account label, or API.

## 2. Required hard gates

Every gate must be PASS with evidence before a candidate can be considered for controlled production evaluation. UNKNOWN is not PASS. A FAIL or UNKNOWN blocks adoption.

| Gate | Required condition | Minimum acceptable evidence |
|---|---|---|
| G1 Administrative separation | Witness administration is not controlled by the scheduler/source administrator or the same unrestricted super-admin. | Provider/account ownership and role map; effective administrator and delegation review. |
| G2 Recovery separation | A person or authority able to restore, roll back, reset, or recover the witness cannot silently control the source scheduler and witness together. | Recovery authority map, recovery-channel review, restore permission evidence. |
| G3 Credential separation | Scheduler/source operators cannot obtain witness write, signing, or recovery credentials through ordinary host, CI, or deployment access. | Identity/role policy, credential issuance and storage design, tested access-denial evidence. |
| G4 Monotonic continuity | Restore, snapshot rollback, deletion/recreation, or migration cannot silently make an old authority generation current. | External continuity checkpoint or independently retained monotonic receipt; restore/replay tests. |
| G5 Conditional append | Competing writers using the same expected sequence cannot both commit. A rejected conditional write must abort the transaction. | Live transactional test against the proposed service; zero-row guard failure demonstrated to abort. |
| G6 Receipt authenticity | Witness receipts are bound to stable transfer ID, task identity, expected prior sequence/hash, new sequence/hash, and witness identity; forgery or substitution is detectable. | Receipt format, signature/MAC custody model, positive and negative verification tests. |
| G7 Independent observation | A reviewer can inspect witness state and relevant audit evidence using separately issued credentials without relying on scheduler-controlled logs. | Reviewer access test and evidence export/read-back. |
| G8 Side-effect fencing | Every destination side-effect boundary rejects stale authority generations; lease expiry or a receipt alone is not treated as fencing. | Destination enforcement design and adversarial stale-generation test. |
| G9 Failure-safe behavior | Witness loss, stale state, contradictory evidence, uncertain append outcome, or unavailable reviewer evidence blocks transfer/activation rather than silently falling back. | Failure matrix and tested fail-closed behavior. |
| G10 Recovery and key transition | Restore, key rotation, account migration, baseline reset, and operator succession require explicit dual control and preserve continuity. | Documented procedure, named independent roles, and tested recovery rehearsal. |

## 3. Evidence grades

Assign each gate one status and cite the evidence artifact:

- VERIFIED: directly observed through a reproducible test or authoritative provider/account metadata, with scope and timestamp.
- PARTIALLY VERIFIED: some subconditions observed, but material coverage is missing.
- PROPOSED: design exists; no live or independent test.
- UNKNOWN: evidence unavailable, connector cannot expose it, or responsible authority has not confirmed it.
- FAILED: evidence contradicts the required condition or a test demonstrates a bypass.

Do not convert connector limitations into claims that a control does not exist. Record “not exposed / not inspected” separately from “absent.”

## 4. Shared-control collision tests

Before PASS, explicitly test these common-mode combinations:

1. Same human or service principal administers source and witness.
2. Same cloud organization super-admin can change both services.
3. Same identity provider or account-recovery email/phone can reset both administrative identities.
4. Same CI runner, repository administrator, deployment token, or host root can write both source state and witness evidence.
5. Same signing-key custodian can forge receipts and rewrite the source.
6. Same backup/restore operator can roll back both witness state and its continuity evidence.
7. Same billing/account owner can suspend, close, or transfer both services.
8. A compromised reviewer credential can change state rather than only observe it.

Any unrestricted common authority that can rewrite both source and witness, or erase both the witness and its independent continuity evidence, is a hard failure unless a separately controlled, tested mechanism prevents or detects it.

## 5. Candidate evaluation using currently inspected evidence

### GitHub — Yggdrasil-Lean

- G1: UNKNOWN — connected personal account is repository admin; full collaborator roster, branch rules, and recovery authority not inspected.
- G2: UNKNOWN — account recovery and organization-level control not established.
- G3: UNKNOWN — CI/repository secret and deployment credential separation not established.
- G4: PROPOSED / not verified — Git history and CI are not an independent monotonic authority witness.
- G5: UNKNOWN — no transactional witness append service established.
- G6: PROPOSED — local receipt concepts exist; independent witness signature custody is not established.
- G7: PARTIALLY VERIFIED — repository/CI evidence is inspectable, but this does not establish an independent witness reviewer domain.
- G8: PROPOSED — fencing contract exists in design; production destination enforcement is not established.
- G9: PARTIALLY VERIFIED — local prototype fails closed in tested cases; this does not establish independent production witness behavior.
- G10: UNKNOWN — account recovery, key rotation, and dual-control recovery not verified.

Assessment: NOT QUALIFIED. GitHub remains source history and CI evidence, not authority witness.

### Airtable — Adaptive Filesystem ChatGPT Pilot

- G1: UNKNOWN — connected account is workspace owner; other workspace members and platform-level administrators not enumerated.
- G2: UNKNOWN — workspace/account recovery and restore authority not established.
- G3: UNKNOWN — writer/service credential inventory not established.
- G4: FAILED AS CURRENTLY PROPOSED — no independent monotonic continuity mechanism established for the pilot.
- G5: UNKNOWN — no suitable conditional append/CAS witness behavior has been demonstrated.
- G6: UNKNOWN — independently verifiable witness receipt and signing-key custody not established.
- G7: UNKNOWN — separate reviewer identity and immutable audit evidence not established.
- G8: PROPOSED — destination fencing is not implemented or tested through Airtable.
- G9: UNKNOWN — production fail-closed authority behavior not established.
- G10: UNKNOWN — recovery and key-transition dual control not established.

Assessment: NOT QUALIFIED. Pilot remains disposable feasibility storage, not canonical authority storage or independent witness.

### Dropbox — connected personal account

- G1: UNKNOWN — personal-account classification is known; account recovery and effective administrative control not inspected.
- G2: UNKNOWN — recovery and restore authority not established.
- G3: UNKNOWN — credential and application access separation not established.
- G4: UNKNOWN — no independent monotonic continuity witness demonstrated.
- G5: UNKNOWN — no transactional conditional append service demonstrated.
- G6: UNKNOWN — witness receipt and signing-key custody not established.
- G7: UNKNOWN — independent reviewer access and audit evidence not established.
- G8: PROPOSED — destination fencing not demonstrated.
- G9: UNKNOWN — fail-closed witness behavior not demonstrated.
- G10: UNKNOWN — recovery and key-transition dual control not established.

Assessment: NOT QUALIFIED. Personal Dropbox may be useful for separately held human-readable checkpoints only if ownership and recovery separation are later established; it is not currently a verified machine authority witness.

## 6. Adoption threshold

A candidate may advance from “not qualified” to “eligible for controlled evaluation” only when:

1. All ten hard gates are VERIFIED, not merely proposed or partially verified.
2. Shared-control collision tests have no unresolved critical bypass.
3. Conditional append and restore/rollback continuity tests pass against the actual service.
4. Destination fencing is tested at every relevant side-effect boundary.
5. An independent reviewer can reproduce the evidence from separately issued credentials.
6. A recovery rehearsal demonstrates that continuity survives restore, migration, key rotation, and operator succession.

Adoption remains a separate explicit decision. Passing this model does not itself authorize task transfer, production activation, or automatic failover.

## 7. Current decision

WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

The three inspected services are not qualified on current evidence. No cloud resources, credentials, permissions, account settings, deployments, or task authority have been changed. No claim is made that unavailable controls are absent.

## 8. Next bounded evidence step

If further inspection is authorized, identify whether any already-existing service has:
- a separately controlled administrator and recovery authority,
- a transactional compare-and-swap or equivalent append primitive,
- an independently retained monotonic continuity record,
- and a reviewer identity outside the source/CI administrator domain.

Do not provision a new service to satisfy the model. Do not request passwords, API keys, recovery codes, or other secrets in chat.
