# Review-History Reference-Model Conformance Review 001

**Status:** Source-backed gap review; recommendations only.
**Sources reviewed:** `ReviewHistoryAdapterTypes.lean`, `ReviewHistoryAppendRules.lean`, `ReviewHistoryAppendInteraction.lean`, the adapter contract, and the integration-test specification.
**Purpose:** Prevent the pure Lean reference model from being mistaken for a complete adapter implementation.

## Findings

### G1 — Identity classification sees one known record

`classifyAppendIdentity` accepts `Option KnownAppend`, so it can classify an intent against one record only. It does not search or enforce uniqueness across a project's full history.

**Consequence:** The existing theorems establish local classification properties, not project-wide event-ID/idempotency-key uniqueness.

**Required next step:** Keep the function as a pure one-record rule, or introduce a separate collection/index-level model before claiming history-wide uniqueness. The concrete adapter must enforce uniqueness atomically.

### G2 — Payload equality is exact String equality

`AppendIntent.payload` and `KnownAppend.payload` are `String`; the model compares them directly. The contract requires equality over all schema-defined immutable logical fields.

**Consequence:** This model does not settle structural equality, serialization equivalence, schema evolution, or missing/unavailable/empty distinctions.

**Required next step:** Do not broaden the theorem claims. Define a schema-level payload representation and equality policy before tests depend on alternate encodings being equivalent.

### G3 — Expected-position validation is a pure comparison

`checkExpectedPosition` compares two natural numbers. The interaction theorem shows a fresh intent with a stale observed position is classified stale, while a known retry resolves before the position check.

**Consequence:** The model does not reserve a position, serialize writers, or prove atomic compare-and-append under concurrency.

**Required next step:** Preserve this as the reference decision order. Verify atomic enforcement with independent concurrent backend clients.

### G4 — Adapter outcome types omit operational detail

`AppendOutcome`, read, ordered-history, reconciliation, and verification types preserve the major outcome families. They currently use simple constructors without structured conflict reasons, retryability, receipts' required fields, completeness metadata, or provenance.

**Consequence:** The vocabulary is useful but is not yet a full executable representation of every contract field. For example, `OrderedHistoryOutcome.page` carries records and an optional cursor but no explicit completeness or snapshot status.

**Required next step:** Before defining a concrete adapter signature, map every contract-required field to a type or explicitly document how it is represented. Preserve unknown, unavailable, incomplete, corrupt, conflict, and unsupported distinctions.

### G5 — Fork lineage type is descriptive, not validated or persisted

`ForkLineage` carries source branch, destination branch, an optional source anchor, and destination genesis event ID. A theorem confirms that `none` remains `none`.

**Consequence:** It does not establish anchor existence, source/destination identity consistency, destination-first sequence position, durable persistence, or source-side visibility.

**Required next step:** Treat the type as a data sketch. Define anchor identity and verification rules before asserting lineage is valid.

### G6 — Verification theorem proves constructor discipline only

`verificationSucceeded` returns true only for `.valid`; the theorem proves this function's behavior over the outcome enum.

**Consequence:** It does not inspect stored bytes, recompute a digest, validate schemas, recover ancestry, or prove the adapter's verification implementation correct.

**Required next step:** Keep digest-based integrity claims deferred. A concrete verifier needs specified inputs/checks and backend evidence; unsupported verification must remain explicit.

## Conformance disposition

| Area | Current reference-model coverage | Missing evidence |
|---|---|---|
| Identity/idempotency | One-record pure classification | Full-scope uniqueness and atomic backend enforcement |
| Payload equality | Exact `String` comparison | Schema-level logical equality and migration behavior |
| Ordering | Pure expected/observed comparison | Atomicity under concurrent writers |
| Outcome semantics | Major outcome constructors | Complete contract metadata and provenance |
| Fork lineage | Descriptive fields and explicit missing anchor | Anchor validation, persistence, placement, and observability |
| Verification | Explicit success/failure outcome discipline | Actual record/schema/lineage/integrity checks |

## Safe implementation boundary

The current model is sufficient to serve as a small pure reference for decision order and result distinctions. It is not sufficient evidence for a durable adapter. Keep implementation claims limited to the exact Lean theorems and CI jobs that have been verified. Do not use a passing build as evidence of persistence, atomicity, restart recovery, or cryptographic integrity.

## Six-Part Integrity Check

1. **Count:** Exactly six gaps, G1–G6.
2. **Roles:** Identity scope; payload equality; atomic ordering; outcome completeness; fork lineage; verification semantics.
3. **Completeness:** Each gap records source behavior, consequence, and a bounded next step.
4. **Status:** Review memo only; recommendations not adopted; no adapter implemented.
5. **Recheck:** The six findings map to the three retrieved Lean modules and adapter contract; no claim of backend behavior is made.
6. **Persistence/uncertainty:** To be verified after saving. Backend choice, schema equality, identifier policy, cursor semantics, and concrete verifier behavior remain unresolved.


## Follow-up: duplicate retry ambiguity outcome (2026-10-27)

The pure reference classifier now returns `ambiguousHistory` when matching retry records identify different event IDs. The backend-neutral adapter outcome vocabulary adds `ambiguousIdentity` to append and reconciliation results. The proposed adapter contract now describes this as distinct from caller conflict and independently established corruption, and the integration-test specification adds corresponding cases under IT-3.

This closes part of the earlier outcome-vocabulary gap for this specific identity ambiguity. It does not complete the general operational metadata mapping described in G4: receipts, retryability, page completeness/snapshot state, structured diagnostics, and provenance still need explicit type-level treatment. The integration scenarios remain specified, not executed; no durable adapter exists.


## Outcome-model mapping review 001 — proposed, not adopted

This review maps the current pure Lean result vocabulary to the adapter contract. It is a design review only: it does not change the Lean types or establish backend behavior.

| # | Concern | Current Lean representation | Contract-level requirement | Gap / bounded next step |
|---|---|---|---|---|
| 1 | Commit receipt and uncertain write | `AppendOutcome Receipt` carries a receipt for committed/already-committed; `commitUnknown` has no handle | Receipt identifies event and position; uncertain commit has a reconciliation handle | Receipt meaning and reconciliation-handle type are absent from the model. Specify a minimal receipt record and opaque handle before selecting fields. |
| 2 | Retryability and failure diagnostics | `unavailable`, `corrupt`, and `conflict` carry no details | Unavailability states retryability only when known; conflict/corruption preserve actionable reasons without secrets | Replace bare constructors only after defining a small, backend-neutral diagnostic vocabulary and an explicit unknown-retryability value. Do not infer retryability from error class alone. |
| 3 | Ordered-page completeness and cursor consistency | `page records nextCursor`, `empty`, `gapOrConflict` | Pages state completeness/gaps; cursor behavior says whether pages share a consistent snapshot | No completeness field or snapshot consistency marker exists. Specify complete/incomplete/unknown and snapshot-bound/non-snapshot/unknown semantics before changing the page result. |
| 4 | Read and assessment provenance | `IdentityReadOutcome.found record` | Distinguish exact stored assessment, reconstruction, and new reassessment; expose missing evidence | Provenance is not represented. Define provenance separately from record content; never label a reconstruction as the original. |
| 5 | Structured validation and schema outcomes | `unsupportedSchema`, `corrupt`, `VerificationOutcome` constructors | Report schema version/details and which checks ran; incomplete evidence is not valid | Current constructors omit details and verification scope. Specify structured findings and checked-scope metadata; digest verification remains deferred until encoding/threat model are adopted. |
| 6 | Identity ambiguity and reconciliation | `ambiguousIdentity` exists for append/reconcile; ordered history still has generic `gapOrConflict` | Competing matching event identities are explicit; history reads and reconciliation preserve evidence and do not choose by order | Append/reconcile distinction is represented, but structured candidate evidence and ordered-history reason detail remain open. Keep the current ambiguity policy proposed, not project-wide adopted. |

### Proposed design constraints

- Keep outcome categories distinct: conflict, ambiguous identity, corruption/invalidity, incompleteness, unsupported schema, unavailable service, and uncertain commit are not interchangeable.
- Treat diagnostic fields as evidence, not as a replacement for the primary outcome.
- Use explicit unknown/unspecified states where the adapter cannot prove retryability, completeness, snapshot consistency, or provenance.
- Avoid expanding the pure reference model into a pretend storage adapter. Storage atomicity, durability, uniqueness, and restart behavior require actual backend tests.
- Before implementation, review a candidate type design against the adapter contract and all six integration-test groups; keep backend-specific details behind the adapter boundary.

### Six-Part Integrity Check

1. **Count:** Exactly six concern rows.
2. **Role mapping:** commit evidence; retry/failure diagnostics; page completeness; provenance; validation/schema evidence; identity ambiguity.
3. **Completeness:** Covers the operational metadata gaps named in G4 and the recent ambiguity work. Identifier policy, logical payload equality, sequence/concurrency, fork placement, canonical encoding, and lifecycle policy remain separate open decisions.
4. **Status:** Proposed design review only; no outcome types changed and no policy adopted by this record.
5. **Recheck:** Each row distinguishes current Lean constructors from contract requirements and names a bounded next step.
6. **Persistence/uncertainty:** This review is saved in the repository; it does not prove an adapter exists, nor that any backend behavior or integration test has passed.
