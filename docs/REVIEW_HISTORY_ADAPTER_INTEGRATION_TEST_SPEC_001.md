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
- If two supplied matching retry records share the project/key/branch/logical payload but have different event IDs, the reference classifier returns `ambiguousHistory` in either record order; the adapter maps this to `AmbiguousIdentity` rather than selecting one event.
- If repeated matching records carry the same event ID, the reference model may resolve to that event, but the adapter must still validate full-history uniqueness and must not infer global uniqueness from an incomplete page.
- When reconciliation by key or event ID discovers multiple competing candidates, it returns `AmbiguousIdentity` and preserves diagnostic evidence rather than reporting committed/already committed.

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


## Boundary-policy mapping (review memo 001)

This mapping links the six integration-test groups to the recommendations in `REVIEW_HISTORY_ADAPTER_BOUNDARY_DECISIONS_001.md`. The recommendations are not adopted policy; test expectations that depend on them remain conditional until the corresponding decisions are explicitly adopted.

| Test group | Boundary dependency | Current test treatment |
|---|---|---|
| IT-1 — Retry after uncertain commit | Logical payload equality | Require same-key/same-logical-payload retries to avoid duplicates. Exact structural equality rules must be defined before implementation tests can assert equivalence across alternate encodings. |
| IT-2 — Concurrent append at same expected position | Expected-position atomicity; cursor consistency if readback paginates | Require backend-level concurrent clients and atomic compare-and-append. A successful Lean model or sequential simulation is insufficient. |
| IT-3 — Event-ID/idempotency-key reuse | Logical payload equality; ID generation/collision handling | Keep cross-encoding equality cases blocked until schema-level equality is specified. Exercise collision handling only once the identifier-generation policy is defined. |
| IT-4 — Ordered history, pagination, recovery | Cursor/snapshot consistency | Test snapshot-bound pagination if supported. Otherwise require explicit non-snapshot labeling and assert that the adapter does not certify a mixed traversal as a complete point-in-time history. |
| IT-5 — Fork lineage and destination genesis | Fork source-side observability; source-anchor semantics | Destination genesis remains the provisional canonical record. Test source-anchor resolution and incomplete lineage. Do not require a source-side event unless that policy is separately adopted. |
| IT-6 — Corruption, unsupported schema, unavailable storage | Payload/schema equality; integrity boundary | Preserve distinct outcomes. Do not claim digest-based integrity until canonical encoding and threat model are specified. |

### Implementation gate

Before selecting a concrete adapter signature or claiming integration coverage, record explicit disposition for:
1. Schema-defined logical payload equality and schema evolution.
2. Event-ID generation and collision handling.
3. Cursor snapshot semantics and expiry behavior.
4. Fork source-anchor representation and whether source-side observability is required.
5. Backend durability and atomic compare-and-append guarantees.
6. Canonical encoding and integrity scope, including whether digest verification remains unsupported in the first milestone.

A disposition may remain **open**; unresolved items must be visible in the implementation plan and tests must not assert unsupported guarantees.

### Six-Part Integrity Check — boundary mapping

1. **Count:** Six integration-test groups mapped.
2. **Roles:** Retry; concurrency; identity reuse; ordered recovery; fork lineage; corruption/schema/service failure.
3. **Completeness:** Each group has a named boundary dependency and a conditional test treatment; unresolved dependencies are enumerated.
4. **Status:** Mapping added to the proposed test specification; no adapter implemented and no integration tests executed.
5. **Recheck:** Group IDs match IT-1 through IT-6 in this document; policy references are to the retrieved boundary memo.
6. **Persistence/uncertainty:** Update is saved in the repository; CI and any downstream implementation remain to be verified.
