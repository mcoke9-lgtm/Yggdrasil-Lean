# Review History Adapter Integration-Test Specification 001

Status: proposed test specification; not an implemented adapter or executed integration-test suite.

## Purpose

Translate the durable review-history decision record and adapter contract into backend-independent integration scenarios. These tests must be run against each concrete adapter and its actual persistence backend before claiming durable, atomic, retry-safe behavior.

This specification does not select a backend, prescribe a concrete adapter signature, or adopt the provisional architecture decisions permanently.

## Test fixture and evidence requirements

Each adapter test run should record:
- adapter implementation and version;
- backend and version/configuration relevant to transactions and durability;
- schema version;
- test-run identifier and timestamp;
- event IDs, idempotency keys, branch IDs, expected positions, returned outcomes, and commit receipts where applicable;
- whether the scenario used one process, multiple workers, or independent backend clients;
- restart/recovery steps and observed history.

Use isolated project/branch fixtures. Do not use production records. Tests must assert semantic outcomes, not merely that calls returned without exceptions.

## Six integration-test groups

### IT-1 — Retry after an uncertain commit

**Setup:** Submit an append with a stable event ID, project-scoped idempotency key, expected branch position, and payload. Simulate or induce a client-side timeout after the backend may have committed but before the client receives a definitive result.

**Actions:**
1. Reconcile the original attempt using the contract's reconciliation operation.
2. Retry with the same event ID, key, branch, expected position, and logical payload.
3. Read the event by identity and read the ordered branch history.

**Assertions:**
- A committed operation can be resolved as committed or already committed with a usable receipt, according to the contract.
- A retry does not create a second logical event.
- If the first attempt was not committed, a valid retry can commit once.
- If the backend cannot establish the result, it reports CommitUnknown/StillUnknown rather than guessing.
- Readback agrees with the reconciled outcome.

**Status:** specified; not executed.

### IT-2 — Concurrent append at the same expected position

**Setup:** Establish branch position P. Prepare two distinct valid append intents, each expecting P, with different event IDs, keys, and payloads.

**Actions:** Submit both appends concurrently through independent clients or workers.

**Assertions:**
- At most one append succeeds at expected position P.
- The losing attempt receives a conflict/stale-position result, or retries only after obtaining a fresh branch position.
- The branch has no duplicate sequence position and no silently overwritten event.
- Ordered history is consistent with the winning commit and any later successful retry.
- The test is repeated enough to exercise the backend's actual transaction/compare-and-append path; a sequential simulation is insufficient evidence for concurrency.

**Status:** specified; not executed.

### IT-3 — Event-ID and idempotency-key reuse

**Cases:**
1. Same project, key, branch, and logical payload: retry resolves to the existing event.
2. Same project and key but different logical payload: conflict.
3. Same project and event ID with a different key: conflict.
4. Same key or event ID in a different project: behavior follows the documented project-scoped uniqueness policy and must not leak or conflate records across projects.

**Assertions:**
- No conflicting identity reuse creates a second event.
- Logical payload equality is defined by the schema/canonicalization policy, not an accidental serialization difference.
- The test must remain blocked or marked provisional until that equality policy is specified.
- Identifier collision handling is observable and does not silently overwrite data.

**Status:** specified with a noted dependency on unresolved canonical logical-payload equality and ID-generation policy; not executed.

### IT-4 — Ordered history, pagination, and recovery

**Setup:** Append a known sequence of events to one branch, including enough events to require multiple pages.

**Actions:**
1. Read the complete ordered history across pages.
2. Restart the adapter process and, where applicable, reconnect to the backend.
3. Read history again using a fresh client.
4. Exercise an invalid, expired, or mismatched cursor if the adapter supports cursors.

**Assertions:**
- All committed events appear once and in the documented order.
- Pagination neither omits nor duplicates events.
- A cursor's snapshot/consistency semantics are documented and respected.
- Missing positions, conflicting positions, or incomplete pages are reported as a gap/conflict or unavailable/incomplete result—not silently presented as complete history.
- Restart does not erase committed history when the backend's durability contract promises restart survival.

**Status:** specified; cursor and snapshot semantics remain an open design decision; not executed.

### IT-5 — Fork lineage and destination genesis

**Setup:** Create a source branch with a known history anchor and explicitly request a fork to a distinct destination branch.

**Actions:**
1. Persist the destination's canonical fork event as its first history event under the provisional decision record.
2. Read the destination history and verify its source anchor and genesis event.
3. Repeat with an unavailable source anchor.
4. Change only the conversation ID without requesting a fork.

**Assertions:**
- A real fork records source branch, destination branch, source anchor when available, and destination genesis event identity.
- Destination history identifies the fork lineage without inventing an unavailable anchor.
- Missing source anchor is reported as incomplete/unavailable lineage.
- Conversation-ID change alone creates no fork event.
- Source-side fork visibility is not assumed; it must follow an explicitly chosen policy.

**Status:** specified; source-side visibility and precise anchor semantics remain unresolved; not executed.

### IT-6 — Corruption, unsupported schema, and unavailable storage

**Setup:** Use controlled fixtures or a test backend to represent a malformed record, unsupported schema version, temporarily unavailable storage, and an incomplete lineage/history case.

**Actions:** Exercise identity read, ordered-history read, reconciliation, and verification as applicable.

**Assertions:**
- Corrupt data yields the contract's corrupt/invalid result.
- Unsupported schema is distinguished from corruption.
- Storage unavailability is distinguished from not-found.
- Verification does not return Valid when required checks cannot be performed.
- Incomplete lineage/history is not mislabeled as valid.
- Error handling does not fabricate a commit receipt or silently convert an unknown commit into a failed or successful commit.

**Status:** specified; integrity threat model, canonical encoding, and exact verification rules remain open; not executed.

## Required release gate

A concrete adapter must not be described as integration-verified until:
1. all six groups have been implemented against the real backend;
2. concurrency cases use genuinely concurrent clients/workers;
3. retry and recovery tests include process restart or equivalent failure injection;
4. the run evidence identifies the exact adapter/backend versions and test results;
5. every failed, skipped, or unsupported case is reported explicitly;
6. known limitations and unresolved policy decisions remain visible in the result.

## Non-claims

This document is a test plan only. It does not establish that an adapter exists, that any test has passed, that persistence is durable, that appends are atomic, or that concurrent behavior is correct.

## Six-Part Integrity Check

1. **Count:** six integration-test groups, IT-1 through IT-6.
2. **Roles:** uncertain retry; concurrency; identity reuse; history recovery; fork lineage; corruption/schema/unavailability.
3. **Completeness:** covers the major adapter-contract outcome families; canonicalization, cursor semantics, source-side fork visibility, and backend-specific guarantees remain unresolved.
4. **Status:** proposed specification; not an implemented suite, not executed, not adopted as a permanent architecture decision.
5. **Recheck:** release gate requires actual backend execution and evidence before making durability or concurrency claims.
6. **Persistence/uncertainty:** document intended for version control; no adapter/test results are created by this specification itself.
