# SCH-001 — Independent witness custody and recovery acceptance protocol

**Status:** proposed design review artifact. This document selects no provider, host, database, account, custodian, or credential. It does not authorize provisioning, installation, account changes, task transfer, or activation.

## 1. Decision boundary

The local D1/workerd prototype has passed 19/19 CI tests on commit `6dde178ee2bad35e31838b82d47fc9a5f0f46478`. Those tests establish behavior inside a disposable CI-runner trust boundary. They do not establish independent custody.

The next gate is not another local hash-chain test. It is evidence that a privileged actor who can alter or restore the scheduler and primary witness cannot also silently rewrite the continuity record used to detect that alteration.

A separate computer, file, process, cloud product, or storage bucket is not automatically an independent failure or administrative domain.

## 2. Assets and threat model

Protect:
- the latest accepted authority generation, event sequence, owner and task fingerprint;
- event identity, previous-event linkage and immutable transition fields;
- acknowledged receipts and the ability to resolve an ambiguous response;
- the source relinquishment and destination fencing evidence;
- recovery history and the identity/authority of each custodian.

Assume an adversary or failure may:
- control the scheduler host or its local administrator;
- control the primary witness host, database, Worker deployment, or provider account administrator;
- restore an older backup, including a mutually consistent data file and local integrity sidecar;
- replace a journal with an older valid copy or rewrite a complete hash chain;
- steal or revoke one service credential, signing key, or operator session;
- cause network partition, power loss, disk-full, clock error, or lost responses;
- exploit a shared identity provider, organization owner, billing owner, recovery email, automation token, backup operator, or support channel to control nominally separate systems.

Out of scope for a first prototype, but must be disclosed in any production claim: simultaneous compromise of every independent custodian, coercion/collusion among all authorized custodians, provider-wide compromise, cryptographic primitive failure, and physical destruction of every retained copy.

## 3. Independence is a vector, not a label

For each proposed primary witness (W) and continuity anchor (A), record evidence for these dimensions:

1. **Administrative authority:** who can create, modify, disable, export, restore, or delete each service?
2. **Identity and credential control:** are owner accounts, identity providers, recovery factors, API tokens, signing keys and rotation paths independent?
3. **Provider and organization control:** are services in separate provider accounts/organizations with distinct owner and support-recovery paths?
4. **Backup and restore control:** who can restore each service, alter retention, delete backups, or rewrite historical state?
5. **Physical and infrastructure control:** are host, power, network, storage, hypervisor and facility failure domains separate where relevant?
6. **Operational control:** are deployment, monitoring, logging, incident response and automation write paths independently controlled?
7. **Custody and review:** who receives receipts, who can verify them, and who can authorize recovery without relying solely on the operator whose actions are under review?

For every dimension record: owner, access path, evidence source/date, shared dependencies, unresolved questions and reviewer. Mark each as **independent**, **shared**, or **unknown**. Do not average dimensions into a score. A shared or unknown critical dimension blocks an independence claim.

At minimum, the primary witness administrator must not be able to alter the anchor's accepted sequence or erase all anchor receipts. The anchor administrator must not be able to rewrite the primary witness's accepted history without producing a detectable mismatch. If one principal can administer both, the arrangement is one administrative domain even if the services are on different machines or providers.

## 4. Roles and minimum topology

- **Scheduler/executor:** requests transitions and performs work only when current fencing is verifiable.
- **Primary witness (W):** validates authorization and transition rules; conditionally appends the authority event; returns a durable, queryable receipt.
- **Continuity anchor (A):** independently validates and conditionally records the digest and monotonic position of each authority event; returns its own durable receipt.
- **Operator/recovery client:** displays status, captures evidence and requests human decisions. It is not an offline authority source.
- **Recovery custodian:** controls a separately reviewed recovery path for the anchor and its receipts.

This is a role model, not a selected deployment. The NUC and Android are only candidates from the existing inventory. The NUC's physical readiness and the handset's installation/model remain unverified. A NUC plus a phone owned and administered through the same credentials, backups and recovery channels does not by itself satisfy independence.

A manually exported, offline-held, independently signed checkpoint can support a controlled human-supervised experiment. It is not an automated remote anchor and must be labelled **manual continuity control**, not production witness.

## 5. Event and receipt protocol

For an event with stable `event_id`, task fingerprint, expected prior sequence/generation/owner and canonical payload digest:

1. Scheduler submits the same stable event ID to W. A timeout is **OUTCOME_UNKNOWN**; never create a replacement ID to escape uncertainty.
2. W authenticates the principal, validates the expected state and transition, then conditionally appends exactly one event. W returns a receipt binding the event ID, task ID/fingerprint, event sequence, generation, owner transition, event hash, schema version and witness identity.
3. A independently verifies the W receipt and its linkage to A's current record. A performs a conditional monotonic append for that same event ID and digest, and returns an anchor receipt binding the W receipt digest, A's own sequence, previous anchor receipt and accepted time.
4. W records the matching anchor receipt and marks the event **CONTINUITY_CONFIRMED**. Destination activation remains blocked until the required source relinquishment and destination fencing evidence are also verified.
5. If either response is lost, query W and A by the original stable event ID. Identical retries return original receipts. Different payloads under the same ID are rejected.
6. If W accepted but A is unavailable, the event remains **PENDING_ANCHOR**. No authority transfer or destination activation is final. Do not roll back by inventing a compensating event or reusing an earlier generation.
7. If W and A disagree, either is stale, an anchor receipt is missing, or an acknowledged event cannot be resolved, enter **QUARANTINED**. Freeze authority changes and activation; preserve both stores and logs for review.

The W and A writes are not one distributed atomic transaction. The protocol deliberately treats partial completion as pending/unknown and fails closed. A later reconciliation must use the original event identity and retained evidence.

## 6. Anchor continuity and recovery

The anchor is not self-authenticating merely because it stores a hash chain. Its own restore path must be assessed.

Required controls before an automated-independence claim:
- A retains monotonic anchor sequence and the latest accepted W receipt digest, with conditional append and stable event-ID idempotency.
- Anchor receipts are independently exportable and verifiable without asking A for the answer.
- Backup deletion, retention changes, restore operations and key rotation are separately authorized and auditable.
- A restore must compare restored state against a separately retained latest receipt or recovery checkpoint. If the restored anchor is behind or conflicts, it must not serve authority decisions.
- Recovery requires two-person or otherwise independently controlled review where feasible; the same administrator whose restore is being checked cannot unilaterally declare continuity restored.
- Recovery creates a new, linked recovery record. It never silently resets the sequence, reuses an old generation, or rewrites accepted history.
- The recovery root and its residual trust assumptions are explicitly documented. Do not imply infinite independence: identify the ultimate human, legal, provider or physical trust roots.

A paper/offline checkpoint is acceptable only for a bounded experiment if its custodian, signing/verification method, receipt date, storage, access and loss procedure are documented. It is not suitable for unattended automated transfer.

## 7. Acceptance tests for the next implementation gate

| ID | Scenario | Required outcome |
|---|---|---|
| CUST-01 | W administrator attempts to alter or delete A's accepted receipt | Denied or independently detected; W cannot silently rewrite A |
| CUST-02 | A administrator attempts to rewrite W's accepted event history | W history remains independently evidenced; mismatch quarantines |
| CUST-03 | W and A share an owner account, identity provider, recovery email or privileged token | Independence assessment blocks approval |
| CUST-04 | W accepts event but A is unavailable | PENDING_ANCHOR; no transfer finalization or activation |
| CUST-05 | A accepts receipt but response is lost | Same event ID query returns original A receipt; no duplicate sequence |
| CUST-06 | W or A is restored to an older internally valid snapshot | External retained receipt detects rollback; service quarantines |
| CUST-07 | Full journal and all local hashes are rewritten by a privileged actor | Independently held receipt detects substitution, or the residual threat is explicitly marked unmitigated |
| CUST-08 | Anchor backup, retention or recovery credentials are changed | Independently logged and reviewed; no silent weakening of retention |
| CUST-09 | Primary witness and anchor disagree on event ID, digest, sequence or generation | Quarantine; no automatic winner selection |
| CUST-10 | Network partition occurs between W and A | No final authority transfer; original event ID remains queryable |
| CUST-11 | Recovery operator is also the sole administrator of both domains | Recovery independence gate fails |
| CUST-12 | Manual offline checkpoint is used | Clearly labelled manual/human-supervised; no claim of automated independence |

Every execution must retain exact service/provider versions, account and role inventory (without secrets), test setup, event IDs, before/after sequence, receipts, logs/evidence digests, reviewer and status (**PROPOSED**, **RUN**, **PASS**, **FAIL**, **BLOCKED**). Synthetic task IDs only; no real task or external side effect.

## 8. Evidence required before choosing a host or provider

Do not select a service from feature marketing or a diagram. Collect:
- actual owner/admin and recovery-role exports for W and A;
- credential issuance, rotation, revocation and recovery dependencies;
- backup/restore permissions, retention and immutable/audit-log behavior;
- documented conditional-append/CAS guarantees and consistency model;
- acknowledged-write durability and lost-response retry semantics;
- independent export and verification path for receipts;
- service outage, quota, suspension and account-recovery behavior;
- evidence that no shared automation or administrator can rewrite both sides.

No account-wide security audit has been completed. Unknowns stay unknown until verified by the account owner or provider evidence.

## 9. Current candidate assessment

| Candidate | Potential role | Current status |
|---|---|---|
| Nine Builders runtime | Scheduler/executor | Existing code/CI; not a witness |
| NUC5i5RYH | Possible W or A host | Physical condition, OS, power/network, custody and administration unverified; no installation authorized |
| Android handset | Operator/recovery client | App installation and device-level tests unverified; not an offline witness |
| Cloudflare D1 prototype | Possible W experiment | Local-only prototype; no remote account/database/Worker created |
| GitHub Actions/repository | Source and CI evidence | Not a monotonic authority witness; branch administration and history are not independent continuity |
| Airtable pilot | Human-readable project register | Not a witness; writer/access inventory and CAS/restore properties unresolved |
| Dropbox | Potential evidence exchange/backup | Project-specific custody and administrative independence not assessed |

No existing service in the inventory is presently verified as an independent monotonic anchor. Do not provision a new database, consensus cluster, account or service until the independence assessment identifies a real gap and the user explicitly approves the proposed change.

## 10. Stop conditions and status

Stop authority transfer and destination activation if:
- either custodian's independence is shared or unknown on a critical dimension;
- conditional append, durable receipts or same-ID retry are not demonstrated;
- either side can be restored or rewritten without independent detection;
- W/A responses are missing, stale, conflicting or ambiguous;
- source relinquishment or destination fencing cannot be independently verified.

**Reviewed:** the local D1 prototype's documented trust boundary and the existing infrastructure inventory.

**Proposed:** independence vector, W/A receipt protocol, recovery requirements and CUST-01–CUST-12 acceptance tests.

**Not implemented or tested:** remote W/A services, cross-provider conditional append, account separation, independent receipt custody, automated recovery, or production fencing.

**Not selected:** NUC, Android, D1, Airtable, Dropbox or any new provider as a production witness.

**Unchanged:** no cloud resources, accounts, credentials, devices, OS, branch protections, production tasks, authority or activation were changed.
