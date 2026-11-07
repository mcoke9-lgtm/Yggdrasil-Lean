# SCH-001 Minimal Witness-Consumer Contract and Failure Semantics

Date: 2026-11-07
Status: PROPOSED INTERFACE — NOT IMPLEMENTED OR CONNECTED TO LIVE EXECUTION

## Purpose

Define the smallest contract by which the existing Nine Builders runtime could request and verify an independent witness decision without gaining authority to create, alter, reset or sign that decision.

This document is an interface and failure-semantics proposal. It does not select a provider, cryptographic library, transport, database, key-management service or production deployment.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## Trust roles

1. **Witness service:** evaluates policy and issues monotonic, scoped decisions and durable receipts. Its signing keys and recovery path are outside scheduler/executor administration.
2. **Consumer:** Nine Builders scheduler/executor. It submits requests and verifies responses but cannot issue witness receipts or reset witness generations.
3. **Destination enforcement point:** the system that performs the external side effect. It independently validates the permit's current generation, scope and operation digest immediately before committing the effect.
4. **Independent reviewer:** obtains evidence through separately authenticated access and reproduces acceptance/failure tests.

A consumer-side check alone is not destination enforcement.

## Proposed data contract

### Authorization request

Required fields:

- protocolVersion
- witnessAudience
- taskId
- runId
- taskDigest (SHA-256 of canonical, versioned task input)
- requestedScope (explicit allowlisted operations and resource identifiers)
- expectedGeneration (the last independently observed generation; null only for first enrollment under an explicit enrollment protocol)
- requestNonce (unique, unpredictable request identifier)
- requestedAt

No credentials, secrets or raw private task contents belong in the request unless a separately reviewed policy explicitly requires them.

### Witness decision

Required fields:

- protocolVersion
- witnessId
- decision: ALLOW, DENY or UNKNOWN
- generation: monotonic integer
- receiptId
- requestNonce
- taskId, runId and taskDigest (exact echo)
- grantedScope (must be a subset of requestedScope)
- issuedAt and expiresAt
- signingKeyId
- signature over a canonical representation of all decision fields

UNKNOWN is a first-class result, never equivalent to ALLOW. DENY and UNKNOWN are terminal for the current attempt unless a new authorized request is made under policy.

### Destination verification request

Required fields:

- permit (the complete signed witness decision)
- destinationId
- operationDigest
- operationScope
- idempotencyKey
- expectedGeneration

The destination must obtain or validate current witness state through a channel that the scheduler cannot forge or downgrade. It must atomically couple generation validation with the side effect, or use an equivalent destination-native fencing primitive. A separate preflight call followed by an unfenced write is insufficient.

### Outcome receipt

Required fields:

- witnessId and generation
- receiptId and permit receipt reference
- taskId and runId
- destinationId
- operationDigest and idempotencyKey
- outcome: COMMITTED, REJECTED or UNKNOWN
- destination transaction/reference identifier when available
- observedAt
- destination identity and signature

A missing outcome receipt after a possibly successful external call means UNKNOWN, not failure and not permission to retry.

## Consumer state machine

States:

- NO_PERMIT
- REQUEST_PENDING
- AUTHORIZED
- DENIED
- UNKNOWN
- EFFECT_PENDING
- EFFECT_COMMITTED
- EFFECT_REJECTED
- QUARANTINED
- RECONCILIATION_REQUIRED

Allowed transitions:

- NO_PERMIT -> REQUEST_PENDING
- REQUEST_PENDING -> AUTHORIZED only after complete signature, identity, nonce, digest, scope, expiry and generation validation
- REQUEST_PENDING -> DENIED or UNKNOWN on a valid corresponding signed decision
- REQUEST_PENDING -> QUARANTINED on malformed, unverifiable, contradictory or mismatched evidence
- AUTHORIZED -> EFFECT_PENDING only when destination enforcement is available
- EFFECT_PENDING -> EFFECT_COMMITTED only with a valid destination outcome receipt
- EFFECT_PENDING -> EFFECT_REJECTED only with a valid destination rejection receipt proving no effect occurred
- EFFECT_PENDING -> RECONCILIATION_REQUIRED when the effect may have occurred but outcome evidence is absent or ambiguous
- Any stale generation, rollback suspicion, key uncertainty, scope mismatch, replay or witness contradiction -> QUARANTINED

No transition from UNKNOWN, DENIED, QUARANTINED or RECONCILIATION_REQUIRED to AUTHORIZED is allowed by local scheduler discretion. Recovery requires a new witness decision or explicit, independently reviewed reconciliation protocol.

## Mandatory fail-closed rules

1. Missing, timed-out or unavailable witness => UNKNOWN; no side effect.
2. Invalid signature, unknown signing key or failed canonicalization => QUARANTINED.
3. Nonce mismatch, task/run/digest mismatch or receipt replay => QUARANTINED.
4. Granted scope broader than requested or operation scope outside grant => QUARANTINED.
5. Expired permit => DENIED for execution.
6. Generation lower than the highest independently observed generation => QUARANTINED.
7. Current generation cannot be established at destination => UNKNOWN; no side effect.
8. Destination cannot enforce generation and scope atomically => no protected side effect; report UNFENCED.
9. External operation may have committed but outcome receipt is missing => RECONCILIATION_REQUIRED; do not automatically retry.
10. Scheduler/executor local database says ALLOW but witness says DENY/UNKNOWN => witness state governs; quarantine discrepancy.
11. Key rotation or witness recovery without a separately witnessed transition => QUARANTINED.
12. Any attempt by consumer identity to create, sign, rewrite or reset witness records => security incident and quarantine.

## Replay and idempotency

- requestNonce identifies one authorization request and cannot be reused for a different request body.
- receiptId is unique within the witness namespace and cannot authorize more than its defined task/run/scope.
- idempotencyKey is stable across retries of the same destination operation; it must not be reused for a different operationDigest.
- An uncertain outcome is resolved by querying the destination using the same idempotencyKey and transaction reference, not by inventing a new key.
- Re-authorization creates a new receipt and must explicitly reference the prior attempt and its outcome status.

## Minimal conformance tests

| ID | Condition | Required result |
|---|---|---|
| WC-01 | Valid signed permit, current generation, exact scope | Accept at destination |
| WC-02 | Witness timeout or unavailable | UNKNOWN; no side effect |
| WC-03 | Invalid signature or unknown key | QUARANTINED |
| WC-04 | Replayed nonce or receipt | QUARANTINED |
| WC-05 | Task/run/digest mismatch | QUARANTINED |
| WC-06 | Scope escalation or mismatch | QUARANTINED |
| WC-07 | Expired permit | Reject; no side effect |
| WC-08 | Stale generation after witness advances | Destination rejects |
| WC-09 | Current generation unavailable to destination | UNKNOWN; no side effect |
| WC-10 | Scheduler restarts with stale local state | Destination still rejects stale permit |
| WC-11 | External effect may have committed; receipt unavailable | RECONCILIATION_REQUIRED; no blind retry |
| WC-12 | Consumer attempts to mint/reset witness receipt | Deny and record security incident |
| WC-13 | Witness restore or key rotation without witnessed succession | QUARANTINED |
| WC-14 | Same idempotency key with different operation digest | Reject and quarantine |
| WC-15 | Local ledger says ALLOW; witness says DENY | Quarantine discrepancy; no effect |
| WC-16 | Destination supports only preflight, not atomic fencing | Mark UNFENCED; do not claim protected execution |

These tests are proposed. No implementation, mock test, live provider test, destination test or independent reproduction was performed in this work.

## Compatibility boundary for Nine Builders

A future adapter may expose a narrow capability such as:

- requestAuthorization(request)
- verifyDecision(decision, expectedContext)
- verifyDestinationReceipt(receipt, expectedContext)

The adapter must not expose:
- issueDecision(...)
- signReceipt(...)
- resetGeneration(...)
- restoreWitness(...)
- rotateWitnessKey(...)

The scheduler may store opaque receipt references and status metadata. It must not treat its local ledger as the authoritative source of witness generation or authorization. The executor must not be allowed to bypass the destination enforcement point.

This interface does not yet specify concurrency/transaction protocol. A production design must prove atomic compare-and-swap or equivalent fencing at both the witness and destination boundaries, including races between authorization, generation advancement and effect commit.

## Adoption gates

Before implementation:
- select an existing candidate service and obtain owner-confirmed, redacted evidence for custody and recovery boundaries;
- prove the witness can issue monotonic generations and conditional append under concurrent requests;
- establish signing-key custody and witnessed key succession;
- identify each external side-effect destination and its native fencing/idempotency capability;
- define canonical serialization, cryptographic algorithm, key identifiers, revocation and clock-skew policy;
- obtain independent reviewer agreement on the threat model and test plan.

Before activation:
- implement in an isolated test environment;
- pass all conformance tests plus the 16 SCH-001 custody adversarial cases;
- run restore, rollback, outage, race and ambiguous-outcome rehearsals;
- independently reproduce tests from separately authenticated evidence;
- obtain explicit user authorization for any deployment or activation.

## Evidence classification

- Interface contract: PROPOSED.
- Failure semantics and 16 conformance cases: PROPOSED.
- Source implementation: NOT IMPLEMENTED.
- Automated tests: NOT RUN.
- Live provider or destination verification: NOT PERFORMED.
- Independent review: NOT PERFORMED.
- Adoption: NOT ADOPTED.

## Integrity boundary

No resources were provisioned, credentials changed, scheduler tasks modified, external effects executed, pull requests merged or authority transferred.
