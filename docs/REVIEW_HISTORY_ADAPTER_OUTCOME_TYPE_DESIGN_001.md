# Review History Adapter Outcome Type Design 001

**Status:** Candidate design for review; not implemented, adopted, or storage-verified  
**Scope:** Backend-neutral outcome metadata for the proposed review-history adapter  
**Related records:** `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, `REVIEW_HISTORY_REFERENCE_MODEL_CONFORMANCE_001.md`, `REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md`

## Purpose and guardrails

Propose a minimal type-level shape for the operational metadata missing from the pure Lean reference model. This is a design artifact, not a concrete adapter API and not a commitment to a backend.

The model must preserve these distinctions: conflict; ambiguous identity; corrupt/invalid data; incomplete evidence; unsupported schema; unavailable service; uncertain commit; confirmed commit; and confirmed absence. Diagnostics supplement the primary outcome; they do not replace it.

Unknown must remain explicit. A type must not imply retry safety, history completeness, snapshot consistency, durable commit, or exact historical provenance unless the adapter has evidence for that claim.

## Candidate vocabulary

These are conceptual shapes, not finalized Lean declarations.

### 1. Diagnostic and retryability

- `Retryability = safeToRetry | unsafeToRetry | unknown`
- `Diagnostic = { code, summary, retryability?, evidenceRefs? }`

The retryability field is meaningful only for failures where retry can be assessed. Omit it or use `unknown` when the adapter cannot establish it. Diagnostic text must not contain secrets. Stable machine-readable codes should be separated from human-readable summaries.

### 2. Commit receipt and reconciliation handle

- `CommitReceipt = { eventId, projectId, branchId, position, backendEvidence? }`
- `ReconciliationHandle = opaque adapter-issued token`

A receipt is returned only after the backend's documented commit boundary is confirmed. The position must be the assigned history position, not a guessed sequence. Backend evidence is optional and its guarantees must be documented. A handle is opaque: callers must not interpret it as proof of commit.

Candidate append results:

- `Committed(receipt)`
- `AlreadyCommitted(receipt)`
- `Conflict(diagnostic)`
- `AmbiguousIdentity(diagnostic, candidateEventIds?)`
- `Unavailable(diagnostic)`
- `Corrupt(diagnostic)`
- `CommitUnknown(handle, diagnostic?)`

Candidate reconciliation results:

- `Committed(receipt)`
- `NotCommitted(evidence)`
- `StillUnknown(handle, diagnostic?)`
- `AmbiguousIdentity(diagnostic, candidateEventIds?)`
- `Conflict(diagnostic)`
- `Corrupt(diagnostic)`
- `Unavailable(diagnostic)`

Candidate IDs are diagnostic evidence only. Their presence does not establish the complete set of conflicting records unless the lookup completeness is also known.

### 3. Ordered-page completeness and cursor consistency

- `Completeness = complete | incomplete | unknown`
- `Consistency = snapshotBound | nonSnapshot | unknown`
- `HistoryPage = { records, nextCursor?, completeness, consistency, diagnostics? }`

A complete empty query is distinct from an unavailable query. A non-snapshot traversal must not be described as a point-in-time snapshot. If the adapter cannot determine completeness or consistency, use `unknown`; do not infer either from a successful page response.

A cursor remains opaque. Expiry, invalidity, and snapshot binding must be documented by the adapter.

### 4. Assessment provenance

- `AssessmentProvenance = storedOriginal | reconstructed(method, version) | reassessed(ruleSnapshot, linkedEventId)`

Provenance accompanies an assessment result and is not inferred from the result's shape. Reconstruction must disclose missing inputs and cannot be labeled as the original. Reassessment is a new linked event and cannot overwrite the original.

### 5. Verification scope and findings

- `VerificationFinding = { code, severity, message, evidenceRef? }`
- `VerificationReport = { outcome, checksPerformed, findings, scope, schemaVersion? }`

The outcome remains one of `valid | invalid | incomplete | unsupportedSchema | unavailable`. `valid` is permitted only for the declared checks actually performed over the declared scope. Missing checks or unavailable evidence must not be silently treated as passed. No digest-based claim is permitted until canonical encoding and threat model are specified.

### 6. Identity ambiguity across operations

Append and reconciliation may return `AmbiguousIdentity` when competing matching records identify different event IDs. Ordered-history reads should retain the existing broad `gapOrConflict` boundary until structured reason semantics are reviewed; if detailed diagnostics are added, they must preserve the distinction between identity conflict, gap, broken lineage, and incomplete lookup.

A verifier may return `invalid` only when a specified invariant is actually disproved. If required history is missing or stale, use `incomplete` rather than declaring a uniqueness violation or declaring validity.

## Mapping to the six integration-test groups

| Test group | Required type-level observability |
|---|---|
| IT-1 — Retry after uncertain commit | receipt, reconciliation handle, explicit unknown outcome, retryability only when established |
| IT-2 — Concurrent append at same expected position | conflict diagnostic, assigned position, evidence of atomic expected-position enforcement from the real backend |
| IT-3 — Event-ID/idempotency-key reuse | conflict vs ambiguous identity, candidate evidence, no order-dependent winner |
| IT-4 — Ordered history, pagination, recovery | completeness, cursor/snapshot consistency, provenance of retrieved or reconstructed assessments |
| IT-5 — Fork lineage and destination genesis | source/destination identity, anchor availability, destination genesis identity, incomplete lineage findings |
| IT-6 — Corruption, unsupported schema, unavailable storage | distinct invalid/corrupt, unsupported, incomplete, and unavailable outcomes; structured findings |

These mappings define observability requirements only. They do not mean the tests are implemented or passed.

## Decisions intentionally left open

1. Exact Lean constructors and whether shared metadata should be represented as structures, wrappers, or separate result records.
2. Stable diagnostic-code vocabulary and whether candidate event IDs may be exposed under access controls.
3. Position representation and whether branch sequence is zero-based, one-based, or uses another cursor model.
4. Receipt's backend-specific durability guarantees and the lifetime/scope of reconciliation handles.
5. Cursor expiry and snapshot-bound pagination support.
6. Provenance schema and references to immutable rule snapshots.
7. Verification check identifiers, schema migration semantics, canonical encoding, and threat model.
8. Whether ordered-history outcomes should gain a structured reason type in the first milestone.

Do not resolve these by assumption while translating this draft into Lean. Review them against the persisted-record schema, decision record, adapter contract, and backend implementation constraints first.

## Six-Part Integrity Check

1. **Count:** Exactly six primary design areas are specified in the mapping: commit/retry metadata; concurrency evidence; identity ambiguity; page consistency/provenance; fork lineage; validation/failure visibility.
2. **Role mapping:** The six IT groups map one-to-one to required observability in the table.
3. **Completeness:** All six integration-test groups are covered. Backend atomicity, uniqueness, durability, encoding, lifecycle, and access-control guarantees remain outside what types alone can prove.
4. **Status:** Candidate design only; no Lean types changed, no policy adopted, no adapter implemented, no integration tests executed.
5. **Recheck:** Primary outcomes remain distinct from diagnostics; unknown is explicit; no constructor or field is described as proof of storage behavior.
6. **Persistence/uncertainty:** This document is intended as a version-controlled design proposal. A saved file proves only that the proposal was recorded, not that its semantics are accepted or implemented.
