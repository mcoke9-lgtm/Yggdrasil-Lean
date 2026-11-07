# SCH-001 — D1 witness prototype and adversarial verification plan

**Status:** design candidate only. This document specifies a bounded prototype and its test plan. It does not claim a deployed D1 service, independent witness, production fencing, or external custody. No account, database, Worker, credential, device, or service is created or changed.

**Baseline preserved:** corrected local/workerd D1 prototype commit `6dde178ee2bad35e31838b82d47fc9a5f0f46478` remains the 19/19 CI-tested baseline. This design is additive and does not alter that baseline or imply that local/workerd behavior proves live-provider behavior.

## 1. Purpose and non-goals

The prototype asks whether a narrow authority-event service can provide:
- serialized conditional append for one authority stream;
- stable event identity and payload-bound idempotency;
- durable receipts that can be queried after an ambiguous response;
- fail-closed behavior when the database or continuity anchor is unavailable;
- detection of a database restore that moves behind independently retained evidence.

It does **not** schedule or execute tasks, activate a destination, provide general-purpose memory, or claim to solve cross-system side-effect fencing. The existing Nine Builders runtime remains the scheduler/executor. Android remains an operator/status/recovery client, not the authority ledger. NUC work remains deferred.

## 2. Trust domains and separation requirements

Treat these as distinct roles, even if an early prototype simulates them:

1. **Scheduler/source operator:** controls the existing runtime and source task process.
2. **D1 application operator:** can deploy or operate the Worker/API but should not hold account-owner recovery authority.
3. **D1 account administrator:** can alter database configuration, credentials, deployments and initiate restore. This is a privileged domain capable of rewriting D1 state.
4. **Continuity-anchor custodian:** controls a separately authenticated append-only checkpoint/receipt destination and its recovery path. Must not be controlled by the D1 account administrator or scheduler host administrator.
5. **Signing-key custodian:** controls the key used to authenticate checkpoint envelopes. Key custody must be separate from ordinary Worker secrets and recoverable without trusting a restored D1 database.
6. **Destination operator:** controls the receiving scheduler/runtime and its activation boundary.
7. **Independent recovery reviewer:** can compare D1 history, anchor history, signed receipts and custody records without having administered the incident system.

A second database, bucket, repository, or account is not automatically an independent trust domain. Independence requires distinct administrative principals, recovery credentials, and restore authority. A second resource under the same Cloudflare account is **not** independent continuity evidence. GitHub source history and CI are useful design provenance but are not accepted as the production authority witness.

## 3. Minimal prototype topology

- **D1 candidate:** one database, one authority stream per scheduler identity, a narrow Worker/API, and no task payloads beyond the minimum task fingerprint and authority metadata.
- **Continuity anchor (required for rollback tests):** a separately administered append-only receipt/checkpoint sink. For the first local prototype, use a deterministic fake anchor adapter with explicit failure injection. A later live integration must select and verify a genuinely separate provider/account and custodian before it can count as independent.
- **Client:** two independently instantiated clients to model competing source/destination requests. Do not rely on one process-local mutex for serialization.
- **Operator:** Android may display status and submit an explicitly approved recovery request, but must not use cached/offline state to authorize a transfer.
- **No live side effects:** prototype requests only append authority proposals/receipts. It must not start jobs, send mail, move funds, mutate external repositories, or activate a destination.

## 4. Minimal data model

Illustrative SQLite/D1 schema; implementation must validate provider SQL compatibility before use.

```sql
CREATE TABLE authority_head (
  stream_id TEXT PRIMARY KEY,
  sequence INTEGER NOT NULL,
  generation INTEGER NOT NULL,
  owner_id TEXT NOT NULL,
  task_fingerprint TEXT NOT NULL,
  head_hash TEXT NOT NULL,
  anchor_sequence INTEGER NOT NULL
);

CREATE TABLE authority_events (
  stream_id TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  generation INTEGER NOT NULL,
  prior_hash TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  event_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY (stream_id, sequence),
  UNIQUE (stream_id, event_id)
);

CREATE TABLE authority_receipts (
  stream_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  generation INTEGER NOT NULL,
  event_hash TEXT NOT NULL,
  receipt_hash TEXT NOT NULL,
  PRIMARY KEY (stream_id, event_id)
);
```

Rules:
- Canonicalize the request before hashing; define canonical JSON encoding, UTF-8, field ordering, number representation and versioning.
- Bind each event to stream ID, event ID, event type, expected sequence, expected generation, owner, task fingerprint, prior event hash and payload hash.
- Use a domain-separated hash construction, e.g. `SHA-256("SCH-001:event:v1:" || canonical_event_bytes)`. Do not reuse a hash across event, receipt and checkpoint domains.
- The receipt is a durable acknowledgement of the D1 append only. It is not evidence that a destination is authorized to execute.
- A duplicate event ID with the same payload hash returns the original receipt. The same ID with a different payload hash is a hard conflict.
- Timestamps are descriptive metadata, never the ordering or fencing authority. Sequence and generation are monotonic counters, not wall-clock time.

## 5. Conditional append protocol

A client submits `Append(stream_id, event_id, expected_sequence, expected_generation, expected_owner, task_fingerprint, event_type, payload)`.

1. Authenticate and authorize the caller; reject missing, expired, wrong-audience or insufficiently scoped credentials.
2. Canonicalize and hash the payload. Check for an existing `(stream_id,event_id)` receipt. If found, return it only when the payload hash and all immutable request bindings match; otherwise reject.
3. In one D1 transaction/batch, conditionally advance the head only if stream ID, sequence, generation, owner, task fingerprint and prior head hash all match the expected values. Insert exactly one event and one receipt bound to the new head.
4. The transaction must abort if the conditional head update affects zero rows or if any insert fails. Do not assume that a batch is safe merely because it is transactional: the implementation must prove that a failed compare-and-swap causes the **whole** transaction to roll back. If the provider's batch API cannot express and enforce this invariant, the design fails this gate.
5. Read back the committed event and receipt by event ID. Return a signed receipt only after durable acknowledgement is established.
6. Independently append the receipt/checkpoint envelope to the continuity anchor. Until the anchor has durably acknowledged the matching sequence and hash, mark the stream **ANCHOR_PENDING / QUARANTINED**. No authority transfer or destination activation is permitted.
7. After anchor acknowledgement, mark the checkpoint as witnessed. If the anchor write may have committed but its response was lost, query by the same stable checkpoint ID and compare the exact payload hash; never create a second logical checkpoint.
8. Any mismatch between D1 and the anchor, unavailable anchor, stale checkpoint, uncertain acknowledgement, or failed authentication leaves the stream quarantined.

Important design choice: the D1 write and external anchor write cannot be one atomic transaction. The protocol therefore has an explicit intermediate pending state and sacrifices availability rather than treating an unanchored D1 write as witnessed authority. Recovery must reconcile by stable IDs and hashes; it must not guess which side committed.

## 6. Transfer and fencing boundary

A future transfer would require a separate protocol:
- destination receipt proves persistence only;
- source must stop accepting new work, drain or explicitly classify in-flight work, and relinquish;
- witness increments generation and records the new owner;
- destination validates the current witnessed generation immediately before every externally visible side effect;
- each downstream system must enforce a stable idempotency key or generation fence where possible;
- if a downstream system cannot enforce fencing, non-idempotent/irreversible operations remain manual-reconciliation only.

A lease timeout, Android approval tap, cached receipt, D1 row, or successful anchor append alone does not activate a destination. No automatic failover is in scope for this prototype.

## 7. Independent continuity envelope

The anchor receives a signed, append-only envelope containing:
- protocol and schema version;
- stream ID, sequence and generation;
- event ID and event hash;
- prior anchored sequence and prior anchored hash;
- current D1 head hash and receipt hash;
- D1 database identity/environment identifier (not a secret);
- signer key ID and signature;
- observed time as informational metadata;
- custody receipt ID returned by the anchor.

The anchor must reject sequence regression, duplicate sequence with changed hash, skipped sequence unless an explicitly reviewed checkpoint-gap policy permits it, and stale prior-anchor hash. It must support query by stable event/checkpoint ID.

The anchor's append history and recovery credentials must survive a D1 restore. Its operator must not be able to silently rewrite old entries; if the selected service cannot provide this property, the prototype may still test interface behavior but must be labelled **simulated**, not independent.

## 8. Restore and recovery protocol

After any D1 restore, suspected rollback, account recovery, credential rotation, database replacement, or unexplained sequence discrepancy:

1. Immediately quarantine the stream; disable transfer and activation.
2. Read the latest anchor entry using independently held credentials.
3. Read the restored D1 head and event/receipt history.
4. Compare stream ID, database identity, sequence, generation, event hash, prior hash, receipt hash and signer key ID.
5. If D1 is behind the anchor, do not replay or synthesize missing authority events. Preserve the restored snapshot for forensics; recover missing evidence from the anchor and independently retained exports.
6. If D1 is ahead of the anchor, treat the suffix as **unwitnessed**. Determine whether a D1 append committed before anchor acknowledgement. Reconcile by stable event IDs and payload hashes; do not activate the suffix.
7. If the anchor itself was restored, rotated, compromised or has uncertain custody, stop. Require a separately retained signed checkpoint or independent custodian confirmation before re-establishing continuity.
8. Resume only after a reviewer records the exact evidence, gaps, key/custody status, decision authority and new baseline. A new generation may be created only through an explicit reviewed recovery event; never silently reset counters.

A restore is not successful merely because the database can be queried. It is successful only when monotonic continuity is proven or the system remains safely quarantined.

## 9. Adversarial test matrix

All tests begin with a known anchored head and record the exact D1 state, anchor state, caller identity and expected outcome. Tests must verify both the returned result **and** unchanged state on rejection.

| ID | Attack / fault | Required outcome |
|---|---|---|
| D1P-01 | Two independent clients append with same expected sequence/generation | Exactly one append is committed and anchored; loser sees conflict; no fork |
| D1P-02 | Stale expected sequence | Reject; D1 and anchor unchanged |
| D1P-03 | Stale generation | Reject; D1 and anchor unchanged |
| D1P-04 | Wrong owner or task fingerprint | Reject; no state change |
| D1P-05 | Retry same event ID and identical payload | Return original receipt; no second event |
| D1P-06 | Reuse event ID with altered payload | Reject conflict; original receipt unchanged |
| D1P-07 | D1 commit succeeds but response is lost | Resolve by same event ID; no duplicate append |
| D1P-08 | Anchor commit succeeds but response is lost | Resolve by same checkpoint ID; no duplicate checkpoint |
| D1P-09 | D1 batch's conditional update affects zero rows | Entire batch aborts; no event or receipt inserted |
| D1P-10 | Later statement in batch fails | Entire transaction rolls back; head/event/receipt unchanged |
| D1P-11 | Anchor unavailable before append | No transfer or activation; explicit quarantine |
| D1P-12 | Anchor unavailable after D1 commit | D1 event remains pending/unwitnessed; no activation |
| D1P-13 | D1 restored behind latest anchor | Rollback detected; quarantine; no counter reset |
| D1P-14 | D1 restored with old head and old local sidecar | Anchor detects regression; local sidecar is not trusted as independent evidence |
| D1P-15 | D1 administrator rewrites full chain and recomputes hashes | Anchor mismatch detected; no activation |
| D1P-16 | Anchor sequence replay, gap, duplicate sequence/different hash | Anchor rejects; no continuity advancement |
| D1P-17 | Anchor operator attempts edit/delete/restore of prior receipt | Denied or independently detectable; otherwise anchor fails independence gate |
| D1P-18 | D1 and anchor credentials stolen from one shared admin domain | Test must show this is a shared-domain compromise; do not claim separation |
| D1P-19 | Signing key rotated, lost or unavailable | Old receipts remain verifiable; new appends stop until authorized key transition is anchored |
| D1P-20 | Network partition / ambiguous timeout / repeated client request | No speculative transfer; stable-ID reconciliation; quarantine if unresolved |
| D1P-21 | Free-tier limit, service suspension or account lock | Fail closed; no offline authority change or activation |
| D1P-22 | Destination presents stale cached generation | Reject at side-effect boundary; if no external fence exists, operation remains disabled |
| D1P-23 | Source has an in-flight non-idempotent operation at transfer | Stop and reconcile; never replay automatically |
| D1P-24 | Android process death, reboot, offline use or duplicate approval tap | UI reports stale/unknown; no authority change from cache or duplicate tap |

## 10. Acceptance gates and status labels

**Prototype design passes only if:**
- conditional append is atomic under competing independent clients;
- duplicate IDs are payload-bound and safe under ambiguous timeouts;
- every rejected operation leaves state unchanged;
- anchor acknowledgement is distinct from D1 acknowledgement;
- D1 restore behind anchor is detected and quarantined;
- anchor custody and restore authority are demonstrably separate, not merely represented by two resources;
- failure, loss, key rotation and recovery all fail closed;
- no destination activation occurs in the prototype.

**Not enough to pass:** local/workerd tests alone; one account with two databases; GitHub commit history; a local hash sidecar; a second API key controlled by the same administrator; a successful restore test without independent evidence; or a receipt that records only a timestamp.

**Current status:**
- **Proposed:** D1 schema, append protocol, separate continuity anchor, quarantine/recovery rules and 24 adversarial cases.
- **Previously CI-verified:** local/workerd D1 suite 19/19 on commit `6dde178ee2bad35e31838b82d47fc9a5f0f46478`.
- **Not yet tested:** this new protocol and D1P-01–D1P-24.
- **Not verified:** live D1 behavior, provider durability contract, external anchor independence, cross-account custody, key recovery, destination fencing, Android device behavior.
- **Not adopted:** D1 as production witness; any cloud account, service, credentials, app installation or automatic failover.

## 11. Stop conditions

Stop authority changes and destination activation on any of:
- D1 or anchor unavailable;
- ambiguous commit outcome not resolvable by stable ID;
- anchor sequence/hash mismatch or regression;
- unexpected D1 restore, database identity change or credential compromise;
- signer key loss, unauthorized rotation or uncertain custody;
- unreviewed gap, fork, duplicate sequence or event rewrite;
- destination unable to enforce the witnessed generation at its side-effect boundary;
- reviewer cannot independently reconstruct the evidence chain.

The safe state is **QUARANTINED / NO TRANSFER / NO ACTIVATION**. Availability is subordinate to preserving authority history.
