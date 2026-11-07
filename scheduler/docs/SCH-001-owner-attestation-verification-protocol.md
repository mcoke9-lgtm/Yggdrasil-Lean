# SCH-001 Owner Attestation Verification and Reviewer Challenge Protocol

Date: 2026-11-07
Status: PROPOSED REVIEW PROTOCOL — NO OWNER ATTESTATION OR INDEPENDENT REVIEW COMPLETED

## 1. Purpose

This protocol prevents an owner-completed account worksheet from being mistaken for proof of independent custody.

The owner worksheet is an intake instrument. Its answers are claims with evidence classifications, not gate passes. A reviewer must verify the material claims, inspect the scope and limitations of evidence, and test for common control paths before changing any SCH-001 gate status.

No account changes, resource creation, credential exchange, deployment, task activation or authority transfer are authorized by this protocol.

## 2. Evidence status vocabulary

Use exactly these distinctions:

- **PROPOSED:** design or test not yet performed.
- **OWNER-ATTESTED:** an owner has stated a fact, but it has not been independently verified.
- **OBSERVED:** a reviewer directly inspected a current provider setting or artifact; observation scope must be recorded.
- **REPRODUCED:** a reviewer independently repeated a test using separately controlled credentials and recorded the result.
- **VERIFIED:** the claim is supported by adequate, current evidence and, where material, independent reproduction.
- **UNKNOWN:** evidence is absent, stale, contradictory, inaccessible or insufficient.
- **FAILED:** evidence demonstrates the requirement is not met.
- **ADOPTED:** a separate explicit adoption decision was made after every hard gate was VERIFIED.

Never silently promote OWNER-ATTESTED or OBSERVED to VERIFIED. Never treat missing evidence as proof of absence or proof of safety.

## 3. Evidence acceptance rules

For each claim, the reviewer records:
1. Exact claim being assessed.
2. Evidence class and source.
3. Observation date and evidence capture date.
4. Account/service scope and roles included.
5. Roles, scopes or recovery paths not included.
6. Whether evidence can be independently authenticated.
7. Whether a second control path could alter the same state.
8. Reviewer identity by role label, conflicts and prior involvement.
9. Result: VERIFIED, UNKNOWN or FAILED.
10. Expiry or re-check trigger.

Screenshots alone are weak evidence when they omit inherited roles, parent organizations, billing ownership, support recovery or service principals. Prefer provider-generated role exports, policy/configuration exports and reproducible tests, while redacting secrets and personal identifiers.

A provider documentation page proves what the provider documents, not how a particular user's account is configured.

An owner statement proves that the owner made the statement, not that the statement is correct.

## 4. Common-control challenge

For each proposed separation, draw a simple authority map and ask whether one actor or recovery path can control both sides.

Inspect these paths separately:
- Primary account administrator.
- Parent organization or team administrator.
- Billing owner and payment recovery.
- Identity provider / SSO administrator.
- MFA reset and account recovery.
- Provider support escalation.
- Service principal, automation token and deployment identity.
- Backup, restore, export and deletion authority.
- Signing-key use, rotation, revocation and recovery.
- Continuity-anchor administration.
- Destination-side authorization and commit authority.

A different username, email address, token, database, project, region or cloud product does not establish independence if a shared administrator, SSO controller, billing recovery path or support process can take over both.

If the authority map is incomplete, status is UNKNOWN.

## 5. Reviewer challenge questions

### Ownership and administrator scope
- Does the evidence include inherited and parent-level roles?
- Can an administrator appoint another administrator or change the role model?
- Is the billing owner distinct, and can billing recovery transfer account control?
- Are provider support and organization recovery powers documented?

### Recovery and continuity
- Can account recovery bypass the claimed two-person or two-domain separation?
- Can a restore replace the witness log and its local receipt?
- Is the continuity anchor held by a genuinely separate owner and recovery process?
- Can a single actor reset both the service and anchor to a new baseline?

### Credential and signing-key separation
- Can scheduler or executor credentials call administrative APIs?
- Can a deployment identity modify schema, policies, retention or audit settings?
- Can an operator use, export or rotate the witness signing key?
- Is key rotation recorded by an independent prior key or dual-controlled process?

### Transaction and destination enforcement
- Does a failed expected-generation condition abort the whole transaction?
- Do concurrent requests with the same expected generation produce exactly one committed winner?
- Does an uncertain append remain UNKNOWN until reconciled?
- Does the destination itself reject stale or mismatched permits atomically at the side-effect boundary?

### Independent reproduction
- Did the reviewer use credentials not issued or controlled by the candidate account administrator?
- Can the reviewer reproduce the receipt and test outcomes from source evidence?
- Are test logs bound to exact code/configuration versions and actual run identifiers?
- Could the person who supplied the evidence have altered both the test and its retained record?

## 6. Minimum challenge sequence

Perform only after the account owner has supplied non-secret evidence and separately authorized any tests. No live test is authorized merely by this document.

1. **Scope review:** reconcile account type, parent organization, all effective admins and service identities.
2. **Recovery review:** map MFA reset, account recovery, billing recovery, support escalation, deletion and restore.
3. **Separation review:** demonstrate that scheduler/executor identities cannot administer witness state, continuity anchor or signing keys.
4. **Anchor review:** identify the independent owner/admin/recovery domain; if none exists, stop.
5. **Transaction test:** in an isolated test resource, run same-generation concurrent append and verify exactly one winner.
6. **Atomicity test:** prove a failed guard aborts all related writes, not merely returns zero affected rows while other statements commit.
7. **Continuity test:** restore or replay a test snapshot and demonstrate that an independently held anchor detects rollback.
8. **Failure test:** simulate timeout, quota exhaustion and witness unavailability; every uncertain state must block side effects.
9. **Destination test:** demonstrate stale-generation rejection at the destination's atomic commit boundary.
10. **Independent reproduction:** a separate reviewer repeats the critical tests and validates evidence provenance.

Stop after any hard-gate failure or unresolved common-control path. Do not continue to later tests merely to produce a larger test count.

## 7. Decision mapping

| Reviewer result | Required action |
|---|---|
| BLOCKED / FAILED | Record failure, preserve evidence, do not qualify candidate |
| UNKNOWN | Request only the missing non-secret evidence; otherwise retain WAIT |
| CANDIDATE_FOR_ISOLATED_TEST | Draft a test plan; obtain separate explicit authorization before creating test resources or running live tests |
| QUALIFIED_FOR_ADOPTION_REVIEW | Present complete evidence package for a separate adoption decision |
| ADOPTED | Only after explicit adoption; record scope, version, owner and rollback/revocation process |

No score or majority vote overrides a failed or UNKNOWN non-compensatory gate.

## 8. Evidence integrity and privacy

- Do not place credentials, tokens, recovery codes, private keys, session cookies or security answers in chat, GitHub issues, commits or this worksheet.
- Do not publish unredacted account identifiers, administrator names, personal emails or screenshots containing secrets.
- Keep raw sensitive evidence in an owner-controlled secure channel; the project repository should contain only redacted summaries, hashes where appropriate, and reviewer conclusions.
- A hash establishes byte identity only when the original bytes and custody are trustworthy; it does not establish truth, origin or completeness by itself.
- Preserve contradictory evidence and record the discrepancy; do not overwrite it with a later preferred statement.
- If evidence is withdrawn, inaccessible or superseded, mark the affected claim UNKNOWN until reverified.

## 9. Current SCH-001 state

- Owner attestation: NOT RECEIVED.
- Reviewer challenge: NOT PERFORMED.
- Cloudflare account availability and custody: UNKNOWN.
- Neon account availability and custody: UNKNOWN.
- Supabase account availability and custody: UNKNOWN.
- Live provider tests: NONE.
- Independent reviewer reproduction: NONE.
- New accounts, projects, databases, Workers or credentials: NONE.
- Permission changes or task activation: NONE.
- Adoption: NONE.
- Decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## 10. Integrity boundary

This is a proposed protocol, not an audit, attestation, certification or implementation. It establishes no facts about provider account settings. All live testing and any resource creation require separate explicit authorization after non-secret evidence review.
