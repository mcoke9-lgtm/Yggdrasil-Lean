# Durable Review-History Implementation Gate — Decision Status Matrix 001

**Status:** Saved decision-tracking matrix; no project-wide decisions are adopted by this document.
**Scope:** Gate for moving from backend-neutral specifications toward a concrete review-history adapter.
**Governing invariant:** A transformation may change a structure's configuration, but must preserve identity and provenance of parts, record changes to relationships, and disclose unrecoverable information.

## Decision-status matrix

| ID | Decision area | Current status | Current recommendation or constraint | Required evidence before implementation claims |
|---|---|---|---|---|
| D1 | Logical payload equality and schema evolution | **Recommended; not adopted** | Compare schema-defined immutable logical fields. Preserve meaningful distinctions such as missing, empty, unavailable, and unknown. Keep receipts and mutable operation status outside the immutable payload unless modeled explicitly. | Written field-by-field equality rules; version/migration behavior; tests for equivalent encodings, changed immutable fields, ordered lists, and unavailable-value distinctions. |
| D2 | Event-ID generation and collision handling | **Open** | Existing provisional model uses caller-supplied stable event IDs and project-scoped uniqueness/idempotency keys. The generation mechanism and collision policy are not selected. | Define ID generation, caller responsibilities, collision detection, scope, retry reuse, and observable conflict behavior. |
| D3 | Cursor consistency, lifetime, and expiry | **Recommended; not adopted** | Prefer snapshot-bound pagination where the backend supports it. Otherwise mark traversal as non-snapshot and do not certify it as a complete point-in-time history. | Define cursor lifetime and invalidation; state snapshot guarantees; test concurrent append, pagination, expiry, restart, and gap detection. |
| D4 | Fork source anchor and source-side observability | **Partly provisional; unresolved** | Provisional baseline puts a canonical fork event first in destination history with a source anchor. Recommendation is not to require a source-side write in the first milestone; source-side discovery limitations must be disclosed. | Define anchor as sequence, stable event ID, or both; behavior when anchor is unavailable; decide whether source-side observability is required; test incomplete lineage and conversation-only changes. |
| D5 | Backend durability and atomic compare-and-append | **Open** | No backend selected. Atomicity and durability may be claimed only to the extent the chosen backend can enforce and document them. | Choose backend and durability boundary; specify transactions and failure model; test independent concurrent writers, uncertain commits, process/backend restart, and readback. |
| D6 | Canonical encoding and integrity verification | **Open; digest claims deferred** | Do not claim digest-based integrity in the first milestone until canonical encoding and a threat model are defined. Preserve unsupported, incomplete, corrupt, and unavailable outcomes distinctly. | Define canonical field order, normalization, absent/empty representations, numeric encoding, schema migration, digest scope/algorithm, and threat model—or explicitly keep digest verification unsupported. |

## Gate rule

A concrete adapter implementation plan may proceed only when each decision is either:
- explicitly adopted by an authorized project decision;
- deliberately left open with a documented implementation restriction; or
- deferred with a clear reason and no claim that depends on it.

A recommendation in this matrix is not adoption. A CI pass validates only the checks that CI actually runs; it does not settle architecture policy, prove persistence, or prove backend atomicity.

## Six-Part Integrity Check

1. **Count:** Exactly six decision rows, D1–D6.
2. **Roles:** Payload semantics; identity generation; read consistency; fork lineage; persistence/concurrency; integrity.
3. **Completeness:** Each row records status, current constraint/recommendation, and required evidence. Backend choice and several detailed semantics remain open by design.
4. **Status:** Matrix is a tracking artifact; recommendations are not adopted; no concrete adapter or integration tests are implemented by this document.
5. **Recheck:** IDs D1–D6 are unique and correspond to six distinct implementation dependencies. The matrix distinguishes partly provisional from open/recommended decisions.
6. **Persistence/uncertainty:** This file is version-controlled after save and retrieval. Current implementation and runtime behavior remain unverified.
