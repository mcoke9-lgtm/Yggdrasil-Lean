# Review History Adapter Outcome Type Design 001

**Status:** Candidate type design for review; semantic rule that competing identities must remain explicit is adopted by `REVIEW_HISTORY_ADAPTER_BOUNDARY_DECISIONS_001.md` Section 4. Constructor shape is not adopted; no implementation or storage verification is claimed.  
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
- `AmbiguousIdentity(diagnostic, observedCandidateEventIds?, lookupEvidence)`
- `UnsupportedSchema(version)`
- `Corrupt(diagnostic)`
- `Unavailable(diagnostic)`

The result family preserves these rules:

- `Found` means the record was retrieved and validated for the declared checks; provenance must identify stored historical data rather than silently substituting reconstruction.
- `NotFound` is allowed only when absence is established within the declared lookup scope. It must not mean permission denial, unavailable storage, incomplete lookup, unsupported schema, or corruption.
- `UnsupportedSchema` means a record exists but this implementation cannot safely interpret its version; it is not equivalent to absence or corruption.
- `Corrupt` requires a validation failure or contradictory required reference actually observed.
- `Unavailable` means a reliable identity-read outcome could not be established. Retryability is included only when supported by evidence.
- If the read finds multiple competing records with different event IDs for one queried identity, return the proposed primary `AmbiguousIdentity` outcome rather than an arbitrary `Found`. The contract revision now proposes the same distinction for identity reads; it remains unadopted pending final API review.

The exact shape of `scopeEvidence` remains open; it must state enough about lookup scope and completeness to distinguish confirmed absence from an incomplete or failed search.

### Identity-read ambiguity disposition — proposed

**Recommendation for review:** Give identity reads a dedicated `AmbiguousIdentity` primary outcome when the requested identity resolves to multiple competing records, rather than representing this condition only as a generic diagnostic attached to `Found` or `Unavailable`.

Rationale:
- `Found(record, provenance)` asserts one validated record is the result; it must not conceal a competing identity match.
- `Unavailable` means the operation could not establish a reliable result; it is not the right category when the lookup did establish a conflict among multiple candidates.
- A diagnostic-only convention is easier for callers to ignore and weakens exhaustive handling of a safety-relevant identity condition. A distinct constructor makes the branch explicit while allowing diagnostics to carry candidate IDs and lookup limits.
- This recommendation is consistent with the contract's append/reconciliation treatment of competing identities, while refining the read-by-identity operation whose listed outcomes do not yet name this case.

Candidate conceptual shape:
- `AmbiguousIdentity(diagnostic, observedCandidateEventIds?, lookupEvidence)`

The candidate list is not asserted complete unless lookup evidence establishes completeness for the relevant identity scope. Do not include inaccessible or unobserved candidates as though they were inspected. If multiple physical matches resolve to the same event ID, that alone does not establish competing event identity; full-history uniqueness and payload consistency remain separate checks.

**Confirmed absence boundary:** `NotFound(scopeEvidence)` remains valid only when the adapter establishes that the identity was absent across the complete declared lookup scope. Incomplete indexes, partial pages, permission failures, unavailable storage, unsupported schema, and corruption cannot be converted into `NotFound`. If the lookup scope or completeness cannot be established, return `Unavailable` or a distinct incomplete/unknown read outcome if the final contract/API defines one; do not guess absence.

**Status:** The semantic invariant is adopted in `REVIEW_HISTORY_ADAPTER_BOUNDARY_DECISIONS_001.md` Section 4: competing event identities must remain explicit, and absence requires complete declared lookup-scope evidence. The proposed primary `AmbiguousIdentity` constructor and its exact fields are not adopted. The persisted-record schema contains event IDs, idempotency keys, and schema-version fields, but does not define lookup-completeness evidence; that evidence belongs to the adapter's read result/diagnostics unless a later reviewed schema decision says otherwise. Review diagnostics, candidate IDs, lookup evidence, incomplete reads, schema failures, corruption, and service unavailability together before changing Lean declarations.

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

1. **Identity read:** the candidate family now includes `AmbiguousIdentity`, matching the contract's proposed identity-read refinement as saved in its latest reviewed revision. This is consistency between two proposals, not adoption. The persisted-record schema supplies record identity fields but does not itself prove lookup completeness; scope evidence must be established by the adapter.
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
4. **Status:** Candidate type design; the identity-ambiguity semantic invariant is adopted in the boundary decision record, but the proposed identity-read constructor/API shape is not adopted. No Lean types changed, no adapter implemented, and no integration tests executed.
5. **Recheck:** Identity-read results align with the contract; `NotCommitted` has a stricter evidence boundary; unknown remains explicit; no field is described as proof of storage behavior.
6. **Persistence/uncertainty:** The original reconciliation was saved at commit `dc3cffa2166db002efbab941451e005bcea43fa5`; the latest decision-alignment revision is saved and read back at commit `d549117258b4a156f44d6e284e465ed23e5c6309`. Persistence records the scoped semantic adoption and proposed API shape, not successful compilation or backend verification.


## Decision alignment — disposition 002

The boundary decision record now explicitly adopts one semantic invariant: multiple competing records with different event IDs must not be resolved by list order or concealed as a single `Found`; `NotFound` requires complete declared lookup-scope evidence. This adoption concerns semantics only. The constructor name, parameters, evidence schema, and representation of incomplete reads remain provisional. No Lean declarations were changed, and no adapter or tests were run.

**Six-Part Integrity Check:** (1) one adopted semantic invariant; (2) identity ambiguity and confirmed absence are its two operational roles; (3) API-shape and evidence gaps remain explicit; (4) semantic rule adopted, constructor proposed, implementation untested; (5) aligned against the current proposed contract and persisted schema; (6) source change must be read back at the resulting commit before claiming persistence.


## Outcome metadata crosswalk — review pass 002

This crosswalk maps the remaining operational information before any Lean constructor decision. It separates the **primary outcome** (what the operation established) from **evidence/diagnostic metadata** (why that conclusion is justified and what remains unknown).

| Concern | Primary outcome or status | Required accompanying metadata/evidence | If evidence is missing |
|---|---|---|---|
| Confirmed commit | Committed / AlreadyCommitted | Receipt: event, project, branch, assigned position; documented commit-boundary guarantee | Do not report success; retain unknown outcome |
| Uncertain write | CommitUnknown / StillUnknown | Opaque reconciliation handle; diagnostic; observed lookup scope, completeness, consistency, retry guarantee when known | Stay unknown; do not guess commit or non-commit |
| Definite non-commit | NotCommitted | Authoritative absence basis over complete relevant identity scope plus documented same-key retry-safety basis | Return unresolved/unknown, not NotCommitted |
| Competing identities | AmbiguousIdentity semantic condition | Observed candidate event IDs where accessible; lookup scope/completeness; diagnostic code and limits | Preserve ambiguity; do not choose by order. Candidate IDs must not be presented as exhaustive without completeness evidence |
| Payload or position conflict | Conflict | Stable diagnostic code, conflicting identity/position context where permitted, evidence reference | Do not collapse into generic unavailability if conflict was established |
| Identity read success | Candidate Found(record, provenance) | Validation scope and provenance: stored original vs reconstruction vs reassessment | Do not label reconstructed/reassessed content as the original |
| Confirmed absence | Candidate NotFound(scopeEvidence) | Declared identity scope and evidence it was searched completely | Partial/unknown lookup cannot establish absence |
| Incomplete or failed lookup | No final constructor selected; unresolved result required | Reason category: partial index/page, permission/access failure, unavailable store, unknown scope/completeness, stale/non-snapshot read | Never map to NotFound; decide between Unavailable and distinct Incomplete/Unknown only after API review |
| Malformed or contradictory stored data | Corrupt (or Conflict where the invariant disproved is a conflict) | Validation finding, affected record/reference, check performed | Do not report absence or validity |
| Unsupported schema | UnsupportedSchema(version) | Observed schema identifier/version and unsupported capability | Do not conflate with absence or corruption |
| Ordered history read | Page result with completeness and consistency; broad gapOrConflict retained provisionally | Ordered records, cursor state, completeness, snapshot/non-snapshot/unknown consistency, diagnostics distinguishing gap, identity conflict, broken lineage where established | Never claim complete snapshot from unknown/incomplete evidence |
| Verification | valid / invalid / incomplete / unsupportedSchema / unavailable | Declared scope, checks performed, findings, schema version | Missing evidence/checks must not become valid |

### Metadata ownership and non-duplication

1. **Persisted event schema:** owns event identity, event payload, lineage, assessment/rule provenance, schema version, and explicitly defined integrity fields. It does not currently define completeness of a particular adapter lookup.
2. **Adapter operation result:** owns operation-specific lookup scope, completeness/consistency evidence, receipt, cursor state, and reconciliation status.
3. **Diagnostic/finding:** owns stable code, human-readable summary, severity where relevant, evidence references, and retryability only when supportable. A diagnostic supplements rather than replaces the primary outcome.
4. **Backend conformance evidence:** owns claims about atomic append, durability, snapshot semantics, access-control behavior, and retry guarantees. Types and documentation alone do not prove these properties.

### Crosswalk conclusions

- The adopted semantic invariant concerns **competing identity and confirmed absence**, not a specific constructor list.
- The strongest unresolved type boundary is **incomplete identity read**: Unavailable may be too broad, but a separate Incomplete/Unknown result should not be added until the contract defines its stable meaning and required evidence.
- Candidate IDs, diagnostics, and retryability are metadata, not substitutes for primary outcomes. Candidate lists are exhaustive only when lookup completeness is established.
- Unsupported schema, corruption, unavailable storage, incomplete evidence, and confirmed absence remain distinct.
- No new persisted-record field is justified by this pass. Lookup completeness remains operation-result evidence unless a future schema review establishes a durable reason to persist it.

**Disposition:** Crosswalk proposed for review; no constructor/API shape adopted, no Lean declarations changed, no adapter implemented, and no tests executed.

## Six-Part Integrity Check — crosswalk 002

1. **Count:** Six integrity roles preserved; the crosswalk has twelve rows because it maps individual outcomes/concerns, not a new six-item framework.
2. **Roles:** Commit certainty; reconciliation; identity; conflict; read provenance/absence; lookup failure; corruption/schema; history consistency; verification.
3. **Completeness:** Required metadata ownership and failure boundaries are mapped. Exact incomplete-read constructor and diagnostic vocabulary remain open.
4. **Status:** Semantic identity invariant adopted separately; this crosswalk is proposed; API and implementation unadopted/untested.
5. **Recheck:** No absence inferred from incomplete evidence; no commit inferred from a handle; no validity inferred from missing checks; no arbitrary identity winner.
6. **Persistence/uncertainty:** Saved and read back at commit `9ec8c9859cc417867b6322cf462b297d81df25c8`; no runtime or backend claim follows from this document.
## Incomplete identity lookup vs service unavailability — decision matrix 001

**Status: recommendation proposed; not adopted. No Lean constructor/API shape is selected by this matrix.**

| Observed condition | What the adapter knows | Safe semantic disposition | Must not claim |
|---|---|---|---|
| Lookup explicitly reports partial coverage (for example, a page/index/scope is known incomplete) | The requested identity search did not cover its declared scope completely | Preserve an explicit incomplete-lookup status/evidence; do not return `NotFound` | That no matching record exists |
| Lookup fails before completeness can be assessed (for example, storage/connectivity/permission failure) | The operation could not establish a reliable result; completeness may be unknown | Preserve an unavailable/unknown result with reason and retryability only when evidenced | That the search was definitely incomplete in a known way, or that the identity is absent |
| Lookup completes across the declared scope and finds no match | Absence within that declared scope is established | Candidate `NotFound(scopeEvidence)` | Global absence beyond the declared scope |
| Lookup completes and finds one validated identity match, with no competing match in the complete declared scope | One matching record is established within that scope | Candidate `Found(record, provenance)` | Global uniqueness outside the declared scope |
| Lookup observes multiple competing event IDs | Competing identity is positively established; candidate-list completeness depends on lookup evidence | Preserve `AmbiguousIdentity` and disclose whether observed candidates are exhaustive | Selecting a winner by list order or calling the result merely unavailable |
| A page/cursor operation returns data but cannot establish whether required scope was fully traversed | Some data is observed, but completeness is unknown | Keep completeness explicitly `unknown`; do not promote empty/partial data to confirmed absence | Complete history or confirmed absence |

### Options considered

- **A — One broad `Unavailable` outcome with reason codes.** Smaller primary outcome family, but risks callers overlooking a known incomplete lookup unless the status is mandatory and exhaustively handled.
- **B — Separate `Incomplete` and `Unavailable` outcomes.** Makes positively known incompleteness explicit, but must not label a failed operation “incomplete” when it never established that fact.
- **C — Separate operation outcome from lookup-completeness metadata (`complete | incomplete | unknown`).** Represents the two axes independently and handles overlapping causes, but adds type/API complexity and may be excessive for the first milestone.

### Recommendation for review

Prefer the **semantic distinction**, not yet a constructor choice: distinguish *known incomplete coverage* from *unavailability before completeness can be established*. Retain an explicit `unknown` completeness state when the evidence does not decide between them. Whether this requires a dedicated `Incomplete` constructor or mandatory completeness metadata should be decided against caller obligations and IT-6 before Lean declarations change.

**Safety rules:** `NotFound` requires complete declared lookup-scope evidence. An incomplete lookup cannot be `NotFound`. A storage/permission failure does not automatically prove known incompleteness; if completeness cannot be assessed, preserve unknown/unavailable. If competing candidates were actually observed, preserve ambiguity even if other parts of the lookup failed.

### Six-Part Integrity Check — matrix 001

1. **Count:** The existing six integration-test groups remain IT-1 through IT-6.
2. **Role mapping:** IT-6 tests read outcome distinctions; IT-3 tests competing identities; IT-4 tests page/cursor completeness.
3. **Completeness:** Matrix separates positive evidence of incompleteness from inability to assess completeness; overlap and candidate-list exhaustiveness remain explicit.
4. **Status:** Semantic recommendation proposed, not adopted; no constructor or Lean declaration changed.
5. **Recheck:** `NotFound` still requires complete scope; unavailable is not absence; observed ambiguity is not hidden.
6. **Persistence/uncertainty:** This is a design proposal only. Backend behavior and test execution remain unverified.

## Caller obligations review — disposition 001

**Recommendation: prefer a distinct incomplete-read semantic branch, with evidence for any observed matches; constructor spelling and exact fields remain unadopted.**

The decisive question is whether a caller can accidentally treat “one match was observed” as “this is the unique matching record.” That is a material safety risk when lookup scope is incomplete. The result must force callers to account for incomplete coverage rather than rely on an optional diagnostic.

### Caller obligations by semantic result

| Semantic result | Minimum caller obligation |
|---|---|
| Complete Found | May use the validated record within the declared scope; must not infer uniqueness outside that scope |
| NotFound | May treat the identity as absent only within the declared scope proven complete by evidence |
| Known Incomplete | Must not treat the lookup as absent or unique; may inspect observed matches only as partial evidence |
| AmbiguousIdentity | Must not choose a winner without an independently justified resolution rule; candidate list may be partial unless completeness is proven |
| Unavailable / completeness unknown | Must not infer absence, uniqueness, commit state, or a known partial-coverage cause |
| UnsupportedSchema / Corrupt | Must not treat the record as absent or silently reinterpret it as a valid record |

### Why the recommendation favors a distinct incomplete branch

1. Incomplete coverage is a positive fact about the lookup, not merely a transient service failure.
2. An optional diagnostic can be ignored; a distinct result branch can be exhaustively handled by callers.
3. An incomplete result may include zero or one observed matching record. It must preserve that evidence without asserting complete-scope uniqueness.
4. If multiple competing event IDs have already been observed, retain AmbiguousIdentity as the primary semantic condition and mark the candidate set non-exhaustive unless completeness is proven. If a later failure prevents completing the search, preserve both the observed ambiguity and the incomplete/unknown coverage metadata.
5. If lookup fails before the adapter can determine whether scope coverage is complete, use unavailable/unknown—not known Incomplete.

This recommendation does not require a particular Lean encoding. An API may represent the distinction with a dedicated Incomplete constructor or an equivalent mandatory tagged result field, but the latter must make omission impossible and must support exhaustive caller handling. A free-form diagnostic is insufficient.

### Disposition

**Proposed, not adopted:** make known incompleteness an exhaustively handled semantic result, separate from unavailable/unknown; carry observed-match and lookup-scope evidence; preserve observed ambiguity even when the candidate set is incomplete. Keep constructor/API fields open until contract and test-spec wording are reconciled. No Lean declarations or implementation changed.

### Six-Part Integrity Check — caller obligations

1. **Count:** The six integration-test groups remain unchanged.
2. **Role mapping:** IT-3 handles competing identities; IT-4 handles traversal completeness; IT-6 verifies caller-visible read-result distinctions.
3. **Completeness:** Covers zero/one observed matches under incomplete scope, multiple observed candidates, and failures before completeness is known.
4. **Status:** Preferred semantic design proposed; no API adoption or implementation.
5. **Recheck:** Known incomplete is not NotFound; one observed match is not complete-scope uniqueness; unavailable is not proof of incompleteness; observed ambiguity remains visible.
6. **Persistence/uncertainty:** Design decision rationale is recorded for review. Caller behavior and backend evidence remain untested.


## Cross-operation outcome inventory and caller crosswalk — review 002

**Status: reconciliation review; semantic inventory proposed, not adopted.** This section reconciles the candidate outcome families with the caller obligations above and the existing IT-6 assertions. It does not select Lean constructor names or fields.

| Operation family | Candidate semantic outcomes | Caller obligation / evidence boundary |
|---|---|---|
| Append | `Committed`, `AlreadyCommitted`, `Conflict`, `AmbiguousIdentity`, `Unavailable`, `Corrupt`, `CommitUnknown` | A receipt must identify the committed event and position. A retry must not duplicate an event. `CommitUnknown` is not `NotCommitted`; ambiguity cannot be resolved by list order. |
| Reconciliation after uncertain append | `Committed`, `NotCommitted`, `StillUnknown`, `AmbiguousIdentity`, `Conflict`, `Corrupt`, `Unavailable` | `NotCommitted` requires authoritative evidence that the original operation did not commit and safe retry cannot duplicate it. Otherwise retain `StillUnknown`. |
| Read by event identity or idempotency identity | `Found`, `NotFound`, `Incomplete`, `AmbiguousIdentity`, `UnsupportedSchema`, `Corrupt`, `Unavailable` | `NotFound` requires complete declared-scope evidence. `Incomplete` preserves observed matches only as partial evidence. `AmbiguousIdentity` remains visible even if candidate evidence is non-exhaustive. |
| Ordered history read / pagination | Results plus completeness (`complete`, `incomplete`, or `unknown`) and consistency (`snapshotBound`, `nonSnapshot`, or `unknown`) | Do not certify complete point-in-time recovery from partial traversal or mixed snapshots. Empty results establish absence only within a fully searched declared scope. |
| Assessment retrieval / reconstruction | Exact stored assessment, reconstruction from stored inputs, new reassessment, or unavailable original | Provenance must distinguish the original stored result from reconstruction or fresh reassessment; an unavailable original is not repaired merely by returning a new assessment. |
| Verification | `valid`, `invalid`, `incomplete`, `unsupportedSchema`, `unavailable` | A partial or unsupported verification cannot be reported as valid. Integrity claims remain limited to the specified evidence and threat model. |

### Reconciliation findings

1. The identity-read family must represent known incomplete coverage as a caller-visible, exhaustively handled semantic state (recommended), while retaining a distinct unavailable/unknown state for failures before completeness is assessable.
2. The same word must not collapse different operation meanings: for example, append `Conflict`, identity-read `AmbiguousIdentity`, and reconciliation `StillUnknown` express different caller obligations.
3. Ordered history needs two separate axes: traversal completeness and snapshot consistency. Neither should be inferred from the other.
4. Append uncertainty is not the same as incomplete identity lookup. An uncertain commit concerns whether a write took effect; incomplete lookup concerns whether a read searched its declared scope.
5. The existing IT-6 subcases cover the identity-read boundary. IT-1/IT-2/IT-4 remain responsible for uncertain retry, atomic concurrency, and ordered recovery respectively; no seventh test group is needed.

### Disposition

**Proposed, not adopted:** retain operation-specific outcome families and mandatory evidence for claims of absence, uniqueness, commit status, history completeness, snapshot consistency, and assessment provenance. Keep exact type constructors/fields open pending complete contract review. No Lean declarations changed; no implementation or tests executed.

### Six-Part Integrity Check — cross-operation review 002

1. **Count:** The integration suite remains exactly six groups, IT-1 through IT-6.
2. **Role mapping:** IT-1 retry uncertainty; IT-2 atomic concurrent append; IT-3 identity/key reuse; IT-4 ordered history and recovery; IT-5 fork lineage; IT-6 corruption/schema/unavailability and identity-read distinctions.
3. **Completeness:** Crosswalk separates write uncertainty, read completeness, snapshot consistency, identity ambiguity, and assessment provenance; backend and canonicalization decisions remain open.
4. **Status:** Inventory and recommendation proposed; API shape not adopted.
5. **Recheck:** `NotFound` requires complete scope; `NotCommitted` requires authoritative evidence; incomplete history is not complete recovery; unsupported/incomplete verification is not valid.
6. **Persistence/uncertainty:** This review is documentation only. No Lean compilation, adapter implementation, or integration-test execution is claimed.

## Current Lean declaration crosswalk — source review 003

**Status:** Source comparison completed; design recommendation remains proposed. No Lean declarations changed and no compiler/test run is claimed.

### Exact declarations retrieved

- `YggdrasilLean/ReviewHistoryAdapterTypes.lean`, blob `ccc3106d06c5e927c6d5b1f23373d9d4cae7117d`.
- `YggdrasilLean.lean`, blob `d38a80ecf9d72e49f5e0c32a34d2cc8a25183c50`, confirming the adapter outcome-types module is imported by the library entry point.
- The proposed contract, integration-test specification, and boundary-decision record were also retrieved and compared.

### Actual current type inventory

`IdentityReadOutcome (Record : Type)` currently has these constructors:

- `found (record : Record)`
- `notFound`
- `unsupportedSchema`
- `corrupt`
- `unavailable`

It has neither an `ambiguousIdentity` constructor nor an `incomplete` constructor, and its `notFound` constructor carries no scope/completeness evidence. This is a mismatch with the adopted semantic invariant and the proposed contract: the invariant is a semantic requirement, but the current type does not yet make callers represent that evidence.

`OrderedHistoryOutcome (Record Cursor : Type)` currently has `page (records) (nextCursor)`, `empty`, `gapOrConflict`, `unsupportedSchema`, and `unavailable`. The page constructor carries no explicit completeness or snapshot-consistency field. The design's separate history-completeness and consistency axes therefore also remain unrepresented in this type. Do not treat this observation as authorization to change that type in the same edit; its constructor shape and first-milestone scope require separate review.

`VerificationOutcome` already has `incomplete`, but this is an operation-specific verification result and does not supply the missing identity-read distinction.

### Proposed semantic crosswalk (not a finalized API)

| Observed condition | Required semantic handling | Current type gap |
|---|---|---|
| One validated match, complete declared scope | Found with scope-bounded provenance/evidence | `found` has no provenance/evidence field |
| No match, complete declared scope | NotFound only with complete-scope evidence | `notFound` carries no evidence |
| Known partial lookup coverage | Explicit incomplete result; observed match is partial evidence only | No incomplete identity-read constructor or mandatory completeness tag |
| Multiple competing event IDs observed | Explicit AmbiguousIdentity; candidate exhaustiveness separately evidenced | No ambiguity constructor |
| Failure before completeness can be assessed | Unavailable/unknown, never NotFound | `unavailable` exists but carries no reason/completeness metadata |
| Ordered page with uncertain traversal completeness or snapshot consistency | Carry completeness and consistency as separate dimensions | `page` carries only records and next cursor |

Recommendation remains to make known incompleteness a caller-visible, exhaustively handled semantic state, distinct from unavailability before completeness can be assessed. If competing IDs have already been observed, ambiguity must remain visible even if the search later fails or its candidate list is incomplete. A single observed match from incomplete coverage must not be presented as unique across the declared scope.

### Scope and decision gate

1. Do not add lookup-completeness fields to every persisted event merely to solve a read-time question; the evidence belongs to the adapter operation result unless a separate schema decision establishes otherwise.
2. Keep append/reconciliation `CommitUnknown` / `StillUnknown` distinct from identity-read incompleteness; they concern different questions.
3. Keep ordered-history completeness separate from snapshot consistency.
4. Before changing Lean declarations, reconcile the exact constructor/evidence design against caller obligations and IT-6; review the ordered-history type as a separate API decision.
5. No constructor spelling, parameters, metadata record, or implementation is adopted by this crosswalk.

### Six-Part Integrity Check — source review 003

1. **Count:** Six integrity roles preserved; this is a source crosswalk, not a new six-test suite.
2. **Roles:** Identity success/absence; known incomplete coverage; competing identities; service failure; ordered-history completeness; snapshot consistency.
3. **Completeness:** Actual identity and ordered-history declarations were retrieved; persisted-schema ownership remains separate from read-operation evidence.
4. **Status:** Semantic invariant adopted; current type gaps verified by source inspection; proposed constructor/evidence shape not adopted; no code changed.
5. **Recheck:** No partial lookup can justify NotFound; ambiguity cannot be hidden; history completeness and snapshot consistency are not conflated.
6. **Persistence/uncertainty:** This section is being added to the design record only. Compilation, runtime behavior, backend capabilities, and integration tests remain unverified.


## Smallest compatible identity-read API proposal — review 004

**Status: proposed for review only; not adopted.** This proposal narrows the next decision to identity reads. It does not change Lean declarations, choose an evidence schema, or bundle ordered-history changes into the same milestone.

### Proposed semantic shape

Retain the existing operation-specific outcomes, but make evidence-bearing results mandatory and add explicit branches for known incomplete coverage and competing identities. Conceptually:

```text
IdentityReadOutcome(
  Record,
  Provenance,
  ScopeEvidence,
  LookupEvidence,
  Diagnostic
)
  = Found(record, provenance, scopeEvidence)
  | NotFound(completeScopeEvidence)
  | Incomplete(observedMatches, lookupEvidence)
  | AmbiguousIdentity(observedCandidates, lookupEvidence)
  | UnsupportedSchema(diagnostic)
  | Corrupt(diagnostic)
  | Unavailable(diagnostic)
```

This is pseudocode, **not Lean source**. The generic evidence types stand in for records that still need review; they are not asserted to exist in the repository. `LookupEvidence` must expose at least whether the declared scope is complete, incomplete, or unknown, and whether the candidate list is exhaustive. `ScopeEvidence` must identify the declared lookup scope and support any claim of complete coverage. `Provenance` must distinguish stored original data from reconstruction or reassessment. `Diagnostic` must not substitute for a primary outcome.

### Required interpretation

- **Found:** one validated record was established within the declared scope, and evidence supports the scope claim. It must not conceal competing event IDs.
- **NotFound:** constructible only with evidence that the complete declared scope was searched and no match was found.
- **Incomplete:** coverage is positively known to be partial. Zero or one observed match remains partial evidence, not proof of absence or uniqueness.
- **AmbiguousIdentity:** multiple competing event IDs were observed. Evidence must say whether the observed candidates are exhaustive. If the lookup later fails or remains partial, preserve that limitation in the evidence rather than selecting a winner.
- **Unavailable:** the operation could not establish a reliable result before completeness could be assessed. Do not use it to erase ambiguity already observed; if ambiguity has been observed, the primary outcome remains ambiguous and the evidence records incomplete/unknown coverage.
- **UnsupportedSchema / Corrupt:** remain distinct from absence and unavailability.

### Compatibility and limits

1. This expands the current identity-read API and changes constructor call sites, so it is **not source-compatible** with callers that construct or pattern-match the existing five constructors.
2. The smallest semantic correction is not necessarily the fewest constructor edits: adding a dedicated `Incomplete` branch and evidence-bearing `NotFound` prevents callers from treating partial coverage as absence. The exact Lean encoding still needs a decision on shared evidence structures and exhaustiveness.
3. Do not add lookup-completeness fields to each persisted event. They describe the read operation, not necessarily the stored event.
4. Keep `AppendOutcome` / `ReconcileOutcome` commit uncertainty separate from identity-read incompleteness.
5. Keep `OrderedHistoryOutcome` out of this change. Its traversal completeness and snapshot consistency are separate API dimensions and need their own reviewed compatibility decision.

### Decision requested by this proposal

Review whether to adopt the **semantic API requirements**—evidence-bearing `Found` and `NotFound`, explicit `Incomplete`, explicit `AmbiguousIdentity`, and separate `Unavailable`—before choosing concrete Lean structures and editing the source file. Until then, do not modify the Lean declarations.

### Six-Part Integrity Check — proposal 004

1. **Count:** Six integrity roles remain; this is an API proposal, not a new test group.
2. **Role mapping:** Found/provenance; confirmed absence; known incomplete coverage; competing identities; failures before completeness is known; schema/corruption.
3. **Completeness:** Overlap between observed ambiguity and partial coverage is retained in lookup evidence; ordered history is explicitly out of scope.
4. **Status:** Semantic invariant adopted earlier; this API shape is proposed, not adopted; Lean code unchanged.
5. **Recheck:** Partial zero/one matches cannot become NotFound or unique Found; ambiguity cannot be erased by a later failure; candidate exhaustiveness is evidence-bounded.
6. **Persistence/uncertainty:** This proposal is being added to the design record and must be read back before persistence is claimed. Lean compilation, caller migration, backend evidence, and tests remain unverified.


## Identity-read semantic requirements — disposition 001

**Status: semantic requirements adopted for the design; concrete Lean encoding remains open.** This disposition adopts the caller-safety requirements below as the basis for the next API draft. It does not adopt the pseudocode in review 004 as a literal declaration and does not authorize editing Lean source yet.

1. A successful identity read must carry the validated record and enough provenance/scope evidence to state what was actually established.
2. `NotFound` must require evidence that the complete declared lookup scope was searched and no matching record was found.
3. Known incomplete coverage must be a caller-visible, exhaustively handled semantic state. Zero or one observed match under partial coverage is not proof of absence or uniqueness.
4. Multiple observed competing event IDs must remain explicitly ambiguous. Candidate evidence must distinguish observed candidates from an exhaustive candidate set.
5. Failure before completeness can be assessed must remain unavailable/unknown, not `NotFound` or known `Incomplete`. If ambiguity was already observed, later failure must not erase it; the outcome/evidence must preserve both the ambiguity and the coverage limitation.
6. Unsupported schema and corrupt evidence remain distinct from absence and service unavailability.
7. Read-time lookup completeness belongs to the operation result/evidence by default, not to every persisted event.
8. Append/reconciliation commit uncertainty and ordered-history completeness/snapshot consistency remain separate operation-specific decisions.

### Next implementation-design gate

Before source changes, choose concrete Lean representations for: (a) scope identity and completeness evidence, (b) record provenance, (c) observed candidate identities and candidate-set exhaustiveness, and (d) diagnostics for schema, corruption, and availability. Then reconcile the resulting constructor pattern against IT-6 and the existing contract. The ordered-history API must be reviewed separately. The new API will require caller migration; source compatibility with the current five-constructor `IdentityReadOutcome` is not presumed.

### Six-Part Integrity Check — semantic disposition 001

1. **Count:** Eight requirements are listed; this is a semantic decision record, not a new six-part artifact or test group.
2. **Role mapping:** Provenance; confirmed absence; incomplete coverage; competing identities; unknown failure; schema/corruption; evidence ownership; operation-family separation.
3. **Completeness:** Explicitly preserves ambiguity alongside partial coverage and leaves ordered-history dimensions separate.
4. **Status:** Semantic requirements adopted for the design; exact types/fields not adopted; Lean source unchanged.
5. **Recheck:** No partial lookup can justify `NotFound`; no single partial match implies uniqueness; a later failure cannot erase already observed ambiguity.
6. **Persistence/uncertainty:** This disposition must be read back after commit. Compilation, caller migration, backend evidence, and tests remain unverified.


## Concrete Lean representation candidate — review 005

**Status: candidate encoding for review; not adopted and not compiled.** This pass chooses a deliberately small type boundary while leaving backend-specific evidence payloads abstract. It is intended to make the semantic requirements expressible without inventing a storage schema or changing persisted events.

### Proposed declarations (illustrative Lean, not yet source)

```lean
inductive LookupCompleteness where
  | complete
  | incomplete
  | unknown
  deriving DecidableEq, Repr

inductive AssessmentProvenance where
  | storedOriginal
  | reconstructed (methodVersion : String)
  | reassessed (ruleSnapshot linkedEventId : String)
  deriving DecidableEq, Repr

/-- Evidence for a fully searched, declared identity scope. -/
structure CompleteScopeEvidence where
  declaredScope : String
  evidenceRef : String

/-- Lookup limits and observations; candidate IDs are exhaustive only when evidenced. -/
structure IdentityLookupEvidence (Record : Type) where
  declaredScope : String
  completeness : LookupCompleteness
  observedMatches : List Record
  observedCandidateEventIds : List String
  candidatesExhaustive : Bool
  evidenceRef : Option String

inductive IdentityReadOutcome
    (Record : Type)
    (LookupEvidence : Type)
    (CompleteScope : Type)
    (Diagnostic : Type) where
  | found (record : Record) (provenance : AssessmentProvenance)
      (scopeEvidence : LookupEvidence)
  | notFound (evidence : CompleteScope)
  | incomplete (observedMatches : List Record) (evidence : LookupEvidence)
  | ambiguousIdentity (observedCandidateEventIds : List String)
      (evidence : LookupEvidence)
  | unsupportedSchema (version : String)
  | corrupt (diagnostic : Diagnostic)
  | unavailable (diagnostic : Diagnostic)
```

This sketch is a design candidate, not a compiler-verified patch. Before adoption, resolve these points:

1. **Evidence types:** decide whether to use the illustrative structures above or adapter-defined opaque evidence types. The `CompleteScope` type parameter in the outcome is intentionally distinct from general lookup evidence so `NotFound` cannot be constructed with the general evidence type by accident; it does not, by itself, prove that a caller's evidence is truthful.
2. **Record provenance:** `AssessmentProvenance` may be too assessment-specific for every event read. If identity reads return general events, rename/generalize it to `RecordProvenance` while retaining stored-original, reconstructed, and reassessed distinctions where applicable.
3. **Candidate representation:** the sketch uses event-ID strings because the current `ForkLineage` uses strings for IDs, but this does not establish a canonical event-ID type. Prefer an existing repository type if one exists after a full source audit.
4. **Overlap:** `ambiguousIdentity` carries lookup evidence so an observed conflict can remain primary when coverage is partial or unknown. Its evidence must disclose whether candidates are exhaustive. The caller must not downgrade it to `incomplete` merely because the search did not finish.
5. **Found scope:** the evidence attached to `found` must state whether the scope was complete. If it was incomplete, the API must not use `found` to imply uniqueness across that scope; either represent the observed record only inside `incomplete` or add a separately reviewed partial-match representation.
6. **No inferred guarantees:** `evidenceRef` is a reference, not proof that a backend is authoritative. The adapter contract must define how evidence is produced and validated.

### Compatibility review

- This changes the current `IdentityReadOutcome Record` type's parameters and constructor payloads, so existing call sites and pattern matches would require migration.
- A less disruptive alternative is a new evidence-bearing wrapper around the existing outcome, but that wrapper must still prevent `notFound` without complete-scope evidence and must expose incomplete coverage as a mandatory state. Merely adding optional diagnostics would not satisfy the adopted requirements.
- No ordered-history constructors are changed in this proposal.
- No lookup-completeness field is added to persisted event records.

### Recommendation

Do **not** copy this sketch into Lean yet. First decide the two unresolved semantic questions that affect constructor safety: whether `Found` is permitted when the declared scope is incomplete, and whether provenance is general record provenance or assessment-specific. Then settle the evidence type boundary and migrate IT-6 assertions to the chosen names.

### Six-Part Integrity Check — candidate encoding 005

1. **Count:** Six integrity roles retained; this is one candidate type sketch, not a new test group.
2. **Role mapping:** complete-scope evidence; general lookup evidence; provenance; incomplete observations; ambiguous candidates; unsupported/corrupt/unavailable failures.
3. **Completeness:** partial coverage and non-exhaustive candidate lists are represented; the `Found`/partial-scope boundary remains explicitly unresolved.
4. **Status:** Candidate encoding only; no constructor/API shape adopted and no Lean source changed.
5. **Recheck:** `NotFound` uses a distinct complete-scope evidence type; ambiguity retains lookup limits; no claim that evidence references prove backend truth.
6. **Persistence/uncertainty:** Save and read-back required. This sketch has not been compiled; caller migration, backend behavior, and tests remain unverified.

## Design decisions and refined type boundary — review 006

This review resolves the two design questions left open by candidate encoding 005. These are **design decisions proposed for review**, not adopted implementation/API decisions. The semantic invariant already adopted in `REVIEW_HISTORY_ADAPTER_BOUNDARY_DECISIONS_001.md` remains controlling.

### Decision 1 — `Found` requires complete-scope evidence

For an identity lookup whose result is intended to establish uniqueness, `Found` must not be returned when the declared lookup scope is known incomplete or its completeness is unknown. A single observed match does not establish uniqueness if another matching record could remain unseen.

- **Complete scope + exactly one validated identity:** eligible for `Found(record, recordProvenance, completeScopeEvidence)`.
- **Complete scope + no matching identity:** eligible for `NotFound(completeScopeEvidence)`.
- **More than one competing identity/event ID:** `AmbiguousIdentity`, retaining the observed candidates and lookup evidence. Do not choose by order.
- **Known incomplete scope, including one observed match:** `Incomplete`, retaining observed matches and the incomplete-scope evidence.
- **Completeness unknown:** do not claim `Found` or `NotFound`. Return an explicitly non-conclusive outcome carrying unknown completeness; the final constructor name/shape remains an API decision. Reserve `Unavailable` for an actual inability to obtain/use the required service or read, rather than using it as a synonym for an inconclusive search.
- **Failure before any reliable lookup status is available:** `Unavailable` may be appropriate, with a diagnostic that does not claim the lookup was complete.

This decision concerns identity uniqueness. If a future API needs to return a *local observed match* under partial coverage, model that as a distinct, explicitly non-unique result; do not overload `Found`.

### Decision 2 — separate record provenance from assessment provenance

A generic identity-read outcome should carry **record provenance**, not an assessment-specific provenance type. Record provenance describes how the returned record itself was obtained or produced. Candidate variants for discussion are:

- `storedOriginal`
- `reconstructed(methodVersion)`
- `migrated(migrationVersion, sourceRef)`

These are candidate semantics; the exact variants and fields still need alignment with the persisted-record schema and actual adapter behavior. Do not assert that reconstruction or migration occurred unless the adapter can supply evidence for it.

**Assessment provenance remains separate** and should appear only in outcomes that actually return or assess an assessment—for example, stored assessment, reconstructed assessment, or reassessment under a named rule snapshot and linked event. It should not be mandatory on arbitrary records.

### Evidence and identifier boundary

- `NotFound` requires a distinct complete-scope evidence type, not a free-form diagnostic or a Boolean flag alone.
- `Found` also needs complete-scope evidence when its meaning promises unique identity within the declared scope.
- `Incomplete` and `AmbiguousIdentity` carry lookup evidence, including the declared scope, completeness status, observed matches/candidates, and a way to identify the supporting read evidence where available.
- An evidence reference is provenance for an audit trail, not proof that the backend was truthful or exhaustive.
- Do not assume a canonical `EventId` type exists. The current Lean source uses `String` fields for fork-lineage identifiers, but that alone does not establish the canonical type for review-event IDs. Before source changes, inspect the persisted schema and all relevant type definitions; otherwise keep candidate identifiers abstract/generic in the design.
- Keep lookup completeness on the adapter operation result. Do not add it to every persisted event merely to solve this read-outcome problem.

### Revised implementation gate

Before changing Lean source, settle the final constructor names and evidence types against the actual persisted schema and adapter boundary, then revise IT-6 so it asserts the semantic rules independently of constructor spelling. In particular, IT-6 must reject a unique `Found` result for a single observed match under incomplete or unknown coverage, preserve competing identities as ambiguous, and allow `NotFound` only with complete-scope evidence.

No Lean source was changed in this review. No compiler or integration tests were run. The proposed distinctions have not yet been adopted as the final public API.

### Six-Part Integrity Check — design decisions 006

1. **Count:** Six integrity roles are checked below; this is a design review, not a six-constructor inventory.
2. **Role mapping:** Scope/uniqueness; record provenance; assessment provenance; evidence boundary; identifier typing; implementation/test gate.
3. **Completeness:** The two prior questions are answered at the semantic level; exact Lean encoding and canonical event-ID type remain unresolved.
4. **Status:** Strict completeness rule and provenance separation are proposed decisions; neither source API nor tests are adopted/verified.
5. **Recheck:** A single observed match under incomplete/unknown coverage cannot become a uniqueness claim; `NotFound` requires complete scope; assessment provenance is not imposed on generic records.
6. **Persistence/uncertainty:** This review must be saved and read back. No Lean compilation, test execution, adapter behavior, or backend evidence is claimed.

## Constructor and IT-6 proposal — review 007

**Purpose:** Turn review 006's semantic boundary into a concrete, reviewable constructor proposal and map it directly to the existing five IT-6 subcases. This remains documentation only; it is not Lean source and has not been compiler-checked.

### Proposed evidence vocabulary

Illustrative Lean sketch:

```lean
inductive LookupCompleteness where
  | complete
  | incomplete
  | unknown
  deriving DecidableEq, Repr

inductive RecordProvenance where
  | storedOriginal
  | reconstructed (methodVersion : String)
  | migrated (migrationVersion sourceRef : String)
  deriving DecidableEq, Repr

structure CompleteScopeEvidence where
  declaredScope : String
  evidenceRef : String

structure IdentityLookupEvidence (Record EventId : Type) where
  declaredScope : String
  completeness : LookupCompleteness
  observedMatches : List Record
  observedCandidateEventIds : List EventId
  candidateSetExhaustive : Bool
  evidenceRef : Option String
```

The `EventId` parameter is deliberately abstract. The retrieved persisted schema names a stable event ID as a logical field but does not define its Lean type or ID-generation policy. `evidenceRef` is an audit reference, not a proof of backend truth. The Boolean `candidateSetExhaustive` is meaningful only alongside the declared scope and completeness evidence; implementation should avoid allowing contradictory combinations.

### Proposed identity-read outcome inventory

Illustrative signature:

```lean
inductive IdentityReadOutcome
    (Record EventId LookupEvidence CompleteEvidence Diagnostic : Type) where
  | found (record : Record) (provenance : RecordProvenance)
      (scopeEvidence : CompleteEvidence)
  | notFound (scopeEvidence : CompleteEvidence)
  | incomplete (evidence : LookupEvidence)
  | ambiguousIdentity (candidateEventIds : List EventId)
      (evidence : LookupEvidence)
  | unresolved (evidence : LookupEvidence)
  | unsupportedSchema (version : String)
  | corrupt (diagnostic : Diagnostic)
  | unavailable (diagnostic : Diagnostic)
```

This proposal makes the following caller obligations explicit:

- `Found` and `NotFound` require the dedicated complete-scope evidence type.
- `Incomplete` represents positive evidence that the declared scope was not fully searched, and retains any observed matches in `LookupEvidence`.
- `AmbiguousIdentity` retains the competing IDs and lookup evidence; the evidence must mark the candidate set non-exhaustive unless the relevant scope was completely searched.
- `Unresolved` represents an inconclusive lookup where completeness is unknown but the operation has returned enough evidence to describe that uncertainty. If the operation itself fails before usable lookup evidence exists, use `Unavailable`.
- `UnsupportedSchema` remains distinct from `Corrupt` and `Unavailable`.
- No constructor selects a winner by list order.

### Evidence-type enforcement caveat

A separate `CompleteScopeEvidence` type makes the required evidence boundary visible in the signature, but does not by itself prove that the adapter actually searched the whole scope. Lean's type alone cannot certify external backend behavior unless construction is restricted behind a trusted/verified boundary. The concrete adapter must produce this evidence according to a documented, testable rule. The exact constructor visibility and any proof-bearing representation are still open.

For a smaller first milestone, a wrapper around the existing result type is possible, but it is acceptable only if callers cannot obtain `Found` or `NotFound` without complete-scope evidence and cannot confuse known-incomplete with unknown/unavailable. The proposed richer inductive type is clearer for exhaustive pattern matching but requires caller migration.

### IT-6 assertion crosswalk

Keep exactly six integration-test groups; these are refinements of IT-6, not a new group.

| IT-6 case | Required semantic assertion | Candidate outcome |
|---|---|---|
| Known incomplete lookup | Preserve partial evidence; never claim full-scope absence or uniqueness | `incomplete(evidence)` |
| Failure before completeness is known | Do not assert known incompleteness or absence; preserve operation failure/unknown status | `unavailable(diagnostic)` if evidence cannot be obtained; otherwise `unresolved(evidence)` |
| Complete scope, zero matches | Absence is valid only with complete-scope evidence | `notFound(scopeEvidence)` |
| Multiple competing IDs observed under partial coverage | Preserve all observed competing IDs and mark candidate set non-exhaustive | `ambiguousIdentity(ids, evidence)` |
| One match observed under incomplete scope | Do not claim the match is unique across the declared scope; retain observed match | `incomplete(evidence)` |

A separate controlled case should check complete scope plus exactly one validated match yields `found(record, provenance, scopeEvidence)`. The test must also confirm that incomplete or unknown coverage cannot produce `Found` or `NotFound`.

### Alignment with inspected records

- **Persisted schema:** stable event ID is listed as currently missing from the Lean model; event-ID type and generation are open. Lookup completeness is not a persisted event field, supporting its treatment as operation-level evidence.
- **Adapter contract:** requires complete-scope evidence for confirmed absence, explicit incomplete-vs-unknown distinction, and visible ambiguity; it leaves exact constructor spelling open.
- **Integration-test specification:** IT-6 already owns corruption, schema, unavailable storage, and the five lookup-completeness cases; no seventh test group is added.
- **Current Lean source:** `IdentityReadOutcome Record` presently has only `found record`, bare `notFound`, `unsupportedSchema`, `corrupt`, and `unavailable`. It has no identity provenance, ambiguity, incomplete, or unresolved constructor. Ordered-history outcomes are separate and are not changed by this proposal.

### Decision status and next gate

Review 007 proposes the above semantic/API shape for review. It does **not** adopt the API or modify Lean declarations. Before source changes, review whether the `unresolved` versus `unavailable` distinction is sufficient for caller needs, whether complete-scope evidence should be proof-bearing or adapter-constructed, and whether provenance variants match actual storage operations. Then update the IT-6 spec wording to match the chosen API while keeping assertions semantic-first.

### Six-Part Integrity Check — constructor proposal 007

1. **Count:** Six integration-test groups remain; five lookup cases plus a positive unique-found case are subcases within IT-6.
2. **Role mapping:** Evidence vocabulary; record provenance; confirmed found/absence; incomplete/ambiguous/unresolved outcomes; failure distinctions; test crosswalk.
3. **Completeness:** Constructor boundaries are concrete enough for review; proof/trust boundary, ID type, and unknown-versus-unavailable encoding remain open.
4. **Status:** Proposed design only; not adopted, not implemented, not compiled, and tests not executed.
5. **Recheck:** `Found` and `NotFound` both require complete-scope evidence; partial single match is incomplete; partial competing IDs remain ambiguous; unknown is not absence.
6. **Persistence/uncertainty:** Save and read back this proposal before reporting it persisted. No source file is changed in this review.

## Evidence trust boundary and inconclusive outcomes — review 008

**Status: recommended design refinement, not adopted API.** This review resolves the three gates listed after review 007 at the semantic level.

### 1. Separate three lookup states

Use three caller-visible outcomes for non-conclusive lookup states:

- **Incomplete:** the operation has positive evidence that only part of the declared scope was searched. Preserve observed matches/candidate IDs and mark the candidate set non-exhaustive unless independently established otherwise.
- **Unresolved:** the lookup operation returned normally, but the available evidence does not establish whether the declared scope was complete. Do not claim unique Found or confirmed NotFound.
- **Unavailable:** the operation could not obtain a usable lookup result because the service/read failed (for example, connectivity, permission, or storage failure). It must not be used as a catch-all for a completed but inconclusive lookup.

This distinction is useful to callers: Incomplete says coverage is known to be partial; Unresolved says completeness could not be established; Unavailable says the operation itself failed to produce a usable result. If a backend failure also provides positive partial-coverage evidence, preserve that evidence in diagnostics/lookup evidence without pretending the service was fully available.

### 2. Treat complete-scope evidence as an adapter trust boundary

A public structure containing only declaredScope and evidenceRef is not proof that a scope was actually searched completely. Recommend a dedicated CompleteScopeEvidence type whose values are constructed only by a narrow adapter validation boundary after the backend-specific completeness condition has been checked. Keep its raw constructor private to the adapter module where practical; expose a checked constructor that requires an adapter-specific completeness witness/result.

This still does not prove arbitrary external storage behavior by Lean's type system alone. The adapter and its evidence-producing boundary remain trusted code unless the backend property is formally modeled and proved. Therefore:

- document exactly what makes a lookup scope complete for each adapter (index coverage, pagination termination, permissions, schema compatibility, and any snapshot requirements);
- do not allow ordinary caller-supplied strings or a Boolean alone to manufacture confirmed absence;
- test the boundary against the real adapter/backend;
- avoid claiming the evidence is proof of truth merely because it has a distinct Lean type.

For the initial backend-neutral type layer, use an abstract evidence parameter in outcome constructors and document that only the adapter boundary may issue it. Defer a universal proof-bearing implementation until the concrete adapter and completeness semantics are known.

### 3. Provenance scope

Retain generic RecordProvenance on a generic identity-read Found result, but do not force assessment provenance into the generic outcome. Keep assessment provenance on assessment-specific retrieval/reconstruction/reassessment operations. The proposed storedOriginal, reconstructed, and migrated variants are only admissible if the adapter can substantiate them; final fields must match the concrete adapter and schema.

### 4. Recommended candidate outcome boundary

The recommended semantic inventory is now:

- Found(record, recordProvenance, completeScopeEvidence)
- NotFound(completeScopeEvidence)
- Incomplete(lookupEvidence)
- AmbiguousIdentity(candidateEventIds, lookupEvidence)
- Unresolved(lookupEvidence)
- UnsupportedSchema(version)
- Corrupt(diagnostic)
- Unavailable(diagnostic)

Ambiguity takes precedence whenever competing event IDs have actually been observed; preserve those IDs even if the search later becomes incomplete or unavailable, and mark the candidate set non-exhaustive unless full-scope evidence exists. Do not choose a winner by order. The inventory is a candidate API, not adopted source.

### 5. Required IT-6 semantics

Keep exactly six integration-test groups. IT-6 must separately cover:
1. positive known-incomplete coverage;
2. operation completes but completeness remains unknown;
3. operation failure before a usable lookup result;
4. complete-scope absence with evidence;
5. competing event IDs observed, including under partial coverage;
6. one observed match under incomplete or unknown coverage, which must not yield unique full-scope Found;
7. complete-scope unique match, which is eligible for Found.

These are subcases of IT-6, not a seventh group. For an observed ambiguity followed by a failed continuation, the outcome must preserve the competing IDs and disclose non-exhaustive coverage; the final constructor packaging remains to be fixed.

### Six-Part Integrity Check — trust boundary 008

1. **Count:** Six integration-test groups remain; the cases above are IT-6 subcases.
2. **Role mapping:** Known partial coverage; unknown completeness; operation failure; confirmed absence; ambiguity; uniqueness; provenance/evidence trust.
3. **Completeness:** The three inconclusive states now have distinct meanings; exact adapter witness and constructor privacy are implementation-specific.
4. **Status:** Semantic recommendation recorded; candidate API remains unadopted and unimplemented.
5. **Recheck:** Unknown completeness cannot become absence; known incomplete cannot become unique success; service failure is not equated with partial coverage; observed competing IDs remain visible.
6. **Persistence/uncertainty:** Save/read-back required. No source change, compilation, backend test, or integration result is claimed.

## Identity-read API reconciliation — implementation candidate 009

**Status:** Proposed source/API shape on branch `review-history-identity-read-evidence-001`; not yet adopted as the project contract and not compiled or tested.

The candidate Lean vocabulary adds `LookupCompleteness` (`complete | incomplete | unknown`), `LookupTermination` (`completed | stoppedEarly | failedAfterObservations`), `RecordProvenance`, and `IdentityLookupEvidence`. The revised `IdentityReadOutcome` has explicit `incomplete`, `unresolved`, and `ambiguousIdentity` cases. `unavailable` may retain optional observations so a failure does not erase what was already observed.

**Disposition for ambiguity followed by failure:** ambiguity is the primary outcome whenever two or more distinct competing event IDs were observed. Its attached lookup evidence retains `failedAfterObservations` when continuation failed. Do not replace it with `unavailable` and lose the observed conflict. If no ambiguity was established, an unavailable result may carry optional lookup observations.

Rules:
- `found` and `notFound` require adapter-issued complete-scope evidence.
- `incomplete` means partial coverage is positively known; `unresolved` means the lookup completed but coverage is unknown.
- Candidate IDs are observed evidence, not an exhaustive set unless completeness is established at the trusted adapter boundary.
- `CompleteEvidence` is only a marker type unless its construction is controlled by the adapter; neither Lean's type nor a caller-supplied Boolean proves backend completeness.
- Provenance constructors remain candidates and must only be used when the adapter can substantiate them.
- The existing event-ID canonical type remains open; this candidate abstracts over `EventId`.

This design change does not add lookup-completeness fields to persisted events. Ordered-history traversal completeness and snapshot consistency remain separate concerns.
