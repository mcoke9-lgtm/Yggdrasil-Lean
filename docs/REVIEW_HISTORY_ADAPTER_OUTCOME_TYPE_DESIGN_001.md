# Review History Adapter Outcome Type Design 001

**Status:** Candidate design for review; not implemented, adopted, or storage-verified  
**Scope:** Backend-neutral outcome metadata for the proposed review-history adapter  
**Related records:** `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, `REVIEW_HISTORY_REFERENCE_MODEL_CONFORMANCE_001.md`, `REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md`

## Purpose and guardrails

Propose a minimal type-level shape for the operational metadata missing from the pure Lean reference model. This is a design artifact, not a concrete adapter API and not a commitment to a backend.

The model must preserve these distinctions: conflict; ambiguous identity; corrupt/invalid data; incomplete evidence; unsupported schema; unavailable service; uncertain commit; confirmed commit; confirmed absence; and identity-read results. Diagnostics supplement the primary outcome; they do not replace it.

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

**Reconciliation evidence boundary (strengthened):**

- `ReconciliationEvidence = { identityQueried, lookupScope, completeness, consistency, authoritativeAbsenceBasis, retryGuaranteeBasis }` is a conceptual minimum, not a finalized record schema. Each field must be backed by adapter-observable evidence or explicitly marked unknown/unavailable; a prose assertion alone is insufficient.
- `NotCommitted(evidence)` is permitted only when the evidence establishes absence of the original operation within the complete, relevant identity scope, and the documented idempotency/commit contract establishes that retrying with the original key cannot create a duplicate or misrepresent the first attempt.
- If lookup completeness, identity scope, freshness/consistency, or retry safety cannot be established, return `StillUnknown`; do not infer absence from an empty partial page, a timeout, an inaccessible store, or a search that covers only one candidate location.
- If multiple competing candidates are found, return `AmbiguousIdentity` with the observed candidate evidence and its lookup limits. Do not choose a winner by list order. Candidate IDs are diagnostic evidence only; they are not a complete set unless lookup completeness is established.
- If independent validation establishes malformed or contradictory stored evidence, report `Corrupt` or `Conflict` as appropriate; do not relabel it as confirmed absence.

This boundary describes the evidence required for a result. It does not claim any current backend can provide that evidence.

### 3. Identity-read result family

The outcome design must cover the contract's separate **read by event identity** operation. Candidate results:

- `Found(record, provenance)`
- `NotFound(scopeEvidence)`
- `UnsupportedSchema(version)`
- `Corrupt(diagnostic)`
- `Unavailable(diagnostic)`

The result family preserves these rules:

- `Found` means the record was retrieved and validated for the declared checks; provenance must identify stored historical data rather than silently substituting reconstruction.
- `NotFound` is allowed only when absence is established within the declared lookup scope. It must not mean permission denial, unavailable storage, incomplete lookup, unsupported schema, or corruption.
- `UnsupportedSchema` means a record exists but this implementation cannot safely interpret its version; it is not equivalent to absence or corruption.
- `Corrupt` requires a validation failure or contradictory required reference actually observed.
- `Unavailable` means a reliable identity-read outcome could not be established. Retryability is included only when supported by evidence.
- If the read can find competing records for one identity, the design must preserve the contract's identity-ambiguity distinction rather than return an arbitrary `Found`. Whether that case is a separate `AmbiguousIdentity` constructor or a structured identity-read diagnostic remains open pending the reference-model and API review.

The exact shape of `scopeEvidence` remains open; it must state enough about lookup scope and completeness to distinguish confirmed absence from an incomplete or failed search.

### 4. Ordered-page completeness and cursor consistency

- `Completeness = complete | incomplete | unknown`
- `Consistency = snapshotBound | nonSnapshot | unknown`
- `HistoryPage = { records, nextCursor?, completeness, consistency, diagnostics? }`

A complete empty query is distinct from an unavailable query. A non-snapshot traversal must not be described as a point-in-time snapshot. If the adapter cannot determine completeness or consistency, use `unknown`; do not infer either from a successful page response.

A cursor remains opaque. Expiry, invalidity, and snapshot binding must be documented by the adapter.

### 5. Assessment provenance

- `AssessmentProvenance = storedOriginal | reconstructed(method, version) | reassessed(ruleSnapshot, linkedEventId)`

Provenance accompanies an assessment result and is not inferred from the result's shape. Reconstruction must disclose missing inputs and cannot be labeled as the original. Reassessment is a new linked event and cannot overwrite the original.

### 6. Verification scope and findings

- `VerificationFinding = { code, severity, message, evidenceRef? }`
- `VerificationReport = { outcome, checksPerformed, findings, scope, schemaVersion? }`

The outcome remains one of `valid | invalid | incomplete | unsupportedSchema | unavailable`. `valid` is permitted only for the declared checks actually performed over the declared scope. Missing checks or unavailable evidence must not be silently treated as passed. No digest-based claim is permitted until canonical encoding and threat model are specified.

### 7. Identity ambiguity across operations

Append and reconciliation may return `AmbiguousIdentity` when competing matching records identify different event IDs. Identity reads must not select an arbitrary matching record if the queried identity resolves to competing records. Ordered-history reads should retain the existing broad `gapOrConflict` boundary until structured reason semantics are reviewed; if detailed diagnostics are added, they must preserve the distinction between identity conflict, gap, broken lineage, and incomplete lookup.

A verifier may return `invalid` only when a specified invariant is actually disproved. If required history is missing or stale, use `incomplete` rather than declaring a uniqueness violation or declaring validity.

## Mapping to the six integration-test groups

| Test group | Required type-level observability |
|---|---|
| IT-1 — Retry after uncertain commit | receipt, reconciliation handle, explicit unknown outcome, evidence-bounded `NotCommitted`, retryability only when established |
| IT-2 — Concurrent append at same expected position | conflict diagnostic, assigned position, evidence of atomic expected-position enforcement from the real backend |
| IT-3 — Event-ID/idempotency-key reuse | conflict vs ambiguous identity, candidate evidence, no order-dependent winner, lookup completeness boundary |
| IT-4 — Ordered history, pagination, recovery | completeness, cursor/snapshot consistency, provenance of retrieved or reconstructed assessments |
| IT-5 — Fork lineage and destination genesis | source/destination identity, anchor availability, destination genesis identity, incomplete lineage findings |
| IT-6 — Identity reads; corruption, unsupported schema, unavailable storage | distinct found/not-found/unsupported/corrupt/unavailable outcomes; confirmed absence evidence; structured findings |

These mappings define observability requirements only. They do not mean the tests are implemented or passed.

## Contract re-review

This revision is checked against the retrieved adapter contract and integration-test specification:

1. **Identity read:** the candidate family now mirrors the contract's `Found`, `NotFound`, `UnsupportedSchema`, `Corrupt`, and `Unavailable` distinctions. Multiple competing matches remain explicit rather than allowing arbitrary selection.
2. **Reconciliation:** the contract's `NotCommitted` rule requires authoritative evidence of non-commit and a safe retry under the documented idempotency guarantee. The strengthened boundary now makes lookup scope, completeness, consistency, and retry guarantee explicit evidence requirements.
3. **No false absence:** empty or incomplete search results, access failures, and unavailable storage cannot justify `NotFound` or `NotCommitted`.
4. **No false success:** a receipt still requires the documented commit boundary; a reconciliation handle remains opaque and does not prove commit.
5. **Integrity limits:** no digest claim is introduced; canonical encoding and threat model remain unresolved.
6. **Test alignment:** IT-1 and IT-3 now name the stronger evidence requirements; IT-6 explicitly includes identity-read outcomes. All mappings remain requirements, not execution evidence.

The contract itself remains proposed, not adopted. This re-review checks consistency with its stated rules; it does not establish implementation feasibility or backend guarantees.

## Decisions intentionally left open

1. Exact Lean constructors and whether shared metadata should be represented as structures, wrappers, or separate result records.
2. Stable diagnostic-code vocabulary and whether candidate event IDs may be exposed under access controls.
3. Shape of identity-read scope/absence evidence and the exact representation of competing matches.
4. Position representation and whether branch sequence is zero-based, one-based, or uses another cursor model.
5. Receipt's backend-specific durability guarantees and the lifetime/scope of reconciliation handles.
6. Cursor expiry and snapshot-bound pagination support.
7. Provenance schema and references to immutable rule snapshots.
8. Verification check identifiers, schema migration semantics, canonical encoding, and threat model.
9. Whether ordered-history outcomes should gain a structured reason type in the first milestone.

Do not resolve these by assumption while translating this draft into Lean. Review them against the persisted-record schema, decision record, adapter contract, and backend implementation constraints first.

## Six-Part Integrity Check

1. **Count:** Six integration-test groups remain, IT-1 through IT-6; the identity-read family is explicitly covered within IT-6 rather than creating an accidental seventh group.
2. **Role mapping:** IT-1 covers reconciliation evidence; IT-3 covers identity ambiguity; IT-6 covers identity-read outcomes and failure distinctions.
3. **Completeness:** The prior omission is addressed. Backend atomicity, uniqueness, durability, encoding, lifecycle, lookup authority, and access-control guarantees remain outside what types alone can prove.
4. **Status:** Candidate design only; no Lean types changed, no policy adopted, no adapter implemented, no integration tests executed.
5. **Recheck:** Identity-read results align with the contract; `NotCommitted` has a stricter evidence boundary; unknown remains explicit; no field is described as proof of storage behavior.
6. **Persistence/uncertainty:** This revision is saved as a repository document if the returned commit is confirmed. That persistence records the proposal only, not semantic adoption, successful compilation, or backend verification.
