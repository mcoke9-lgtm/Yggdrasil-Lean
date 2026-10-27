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
