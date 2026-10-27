# Review History Adapter Boundary Decisions 001

**Status:** Evaluation memo with recommendations for review; no recommendation in this memo is adopted automatically.
**Parent records:** `DURABLE_HISTORY_DECISION_RECORD_001.md`, `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, and `REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md`.
**Purpose:** Narrow three dependencies that block a concrete adapter integration-test implementation without choosing a backend or claiming implementation exists.

## 1. Logical payload equality

### Problem

The Lean reference model currently uses exact `String` equality for its payload. The adapter contract instead requires equality over all schema-defined immutable logical fields. Those are not interchangeable: serialization changes should not silently change event identity, and missing/unavailable values must not collapse into empty values.

### Options

- **E1 — Raw encoded-byte equality.** Simple, but representation changes can make semantically equivalent records compare unequal. Not suitable as the contract's logical equality rule.
- **E2 — Schema-defined structural equality.** Compare each immutable field according to its schema meaning, preserve list order where order is meaningful, and distinguish absent, empty, unavailable, and unknown values. Keep commit receipts and mutable adapter-operation status outside the immutable payload unless explicitly modeled as event data.
- **E3 — Canonical serialized-byte equality.** Define a canonical encoding first, then compare bytes or a digest. This requires explicit encoding, normalization, and migration rules that have not yet been settled.

### Recommendation for review

Use **E2 as the logical contract**, with E3 deferred until a canonical encoding is justified. E2 does not itself dictate a wire format. For any text field, do not silently normalize Unicode, case, whitespace, or line endings unless that field's schema explicitly requires it. A future canonical encoding must preserve E2 equality.

### Required test consequences

- Equivalent structural payloads represented by different encodings resolve to the same logical event.
- Different immutable field values conflict.
- Missing, empty, unavailable, and unknown remain distinct where the schema distinguishes them.
- Order-sensitive lists preserve order.
- Commit receipts or mutable operation status do not change event-payload identity.

**Status:** recommendation only; no canonical encoding or production equality implementation is selected.

## 2. Cursor and ordered-history consistency

### Problem

A cursor must not imply a complete consistent history if pages were read from changing branch states. The adapter contract permits either a consistent snapshot or an explicit statement that pages are not a consistent snapshot.

### Options

- **C1 — Snapshot-bound cursor.** The first page establishes a stable history view; continuation pages refer to that view. Cursor expiry or snapshot loss returns an explicit unavailable/expired result, never a silently mixed complete history.
- **C2 — Live cursor.** Pages may include appends committed after the first page. The API explicitly states that the traversal is not a single snapshot and exposes enough information to detect gaps or duplicates.
- **C3 — Backend-dependent semantics.** Each adapter chooses its behavior and documents it. This is flexible but makes portable recovery tests and caller assumptions harder to maintain.

### Recommendation for review

Prefer **C1 for the recovery/read-completeness contract** when the backend can support it. If it cannot, C2 is acceptable only when the API marks the traversal as non-snapshot and does not report it as a complete point-in-time history. Do not pretend every backend can provide snapshot semantics.

### Required test consequences

- Concurrent appends during pagination never create a falsely certified complete snapshot.
- Snapshot-bound cursors return the same logical view or a documented expiry/unavailable result.
- Live cursors disclose non-snapshot semantics and expose gaps/conflicts according to the contract.
- Restart, cursor expiry, and invalid cursor behavior are explicitly tested.

**Status:** recommendation only; cursor representation, lifetime, and snapshot mechanism remain unselected.

## 3. Fork source-side observability

### Problem

The provisional decision record places the canonical fork event first in destination history and does not require atomic writes to both branches. The open question is whether source history must also contain a fork record.

### Options

- **F1 — Destination canonical event only.** Destination history contains the canonical fork event and source anchor. Source history is unchanged. Source-side lookup may not directly show that a fork occurred.
- **F2 — Canonical destination event plus source-side event.** Both histories receive a record. This improves local visibility but creates partial-write and cross-branch consistency questions unless the backend offers a suitable transaction or a reconciliation protocol.
- **F3 — Destination canonical event plus derived index.** Destination history remains authoritative; a rebuildable index may answer source-side queries. Index staleness, availability, and provenance must be visible and it must never be treated as canonical history.

### Recommendation for review

For the first adapter milestone, prefer **F1 as the canonical persistence rule**, while documenting the source-side observability limitation. Consider F3 later if source-side discovery is required. Do not adopt F2 without a separately specified cross-branch failure/reconciliation protocol.

### Required test consequences

- Destination history contains the fork event and the best available source anchor.
- An unavailable source anchor is reported as incomplete/unavailable lineage, not invented.
- Conversation-ID change alone creates no fork event.
- No source-side record is assumed unless an explicit policy adds one.
- If a derived index is later introduced, tests prove it can be rebuilt from canonical records and disclose stale/unavailable states.

**Status:** recommendation only; the existing provisional destination-history decision remains the current baseline, and source-side observability is not resolved by this memo.

## Six-Part Integrity Check

1. **Count:** Exactly three boundary decisions: payload equality, cursor consistency, fork observability.
2. **Roles:** Logical identity; ordered-history completeness; branch-lineage discoverability.
3. **Completeness:** Each includes options, a recommendation for review, test consequences, and remaining limits. Backend choice, canonical encoding, cursor mechanics, and cross-branch atomicity remain open.
4. **Status:** Saved evaluation memo; recommendations are not adopted decisions; no adapter or tests implemented.
5. **Recheck:** Recommendations preserve the contract's distinctions among conflict, unknown commit, incomplete lineage, unsupported verification, and unavailable service.
6. **Persistence/uncertainty:** This memo is version-controlled; it does not update the prior decision record or convert provisional choices into project-wide invariants.
