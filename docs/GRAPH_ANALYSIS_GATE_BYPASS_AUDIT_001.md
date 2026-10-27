# Graph-Analysis Gate Bypass Audit 001

**Status:** updated 2026-10-27 after internal-module separation and read-only pilot snapshot testing. This is a reference-model architecture audit, not a production security guarantee.

## Scope

- `reference-model/verified-graph-consumer.mjs`
- `reference-model/graph-input-certificate.mjs`
- `reference-model/review-history-resolver.mjs`
- `reference-model/review-history-resolver.internal.mjs`
- `reference-model/review-history-resolver.test.mjs`
- `reference-model/pilot-snapshot.mjs`
- Lean graph audit/evidence definitions and CI workflows

## Current entry-point inventory

| Entry point | Gate enforced? | Current interpretation |
|---|---|---|
| `analyzeVerifiedGraphInput(serialized, options)` | Yes | Gated reference-model analysis; it owns the analyzer and does not accept a caller-supplied callback. |
| `consumeVerifiedGraphInputArtifact(...)` | Validation only | Returns graph input only when artifact and completeness conditions pass; it is not itself an analysis operation. |
| Public `review-history-resolver.mjs` | Raw graph analyzers not exported | Exposes the non-graph resolver contract (`Outcome`, `mapGraphOutcomeForLean`). |
| `review-history-resolver.internal.mjs` | Internal-by-convention | Contains raw analysis functions for the gated consumer and tests. The module system itself does not make its exports cryptographically inaccessible. |
| Lean graph classifiers and predicates | No runtime artifact gate | Lean consumes supplied values and does not parse the JavaScript artifact or independently recompute its SHA-256 digest. |

## Read-only source snapshot test

The Airtable connector was queried read-only against base `appaUa8VbUJZmzmxe`, table `tblvy8J5UC6Y6SylE` (`Records`). The connector returned 10 records, reported `totalRecordCount=10`, and returned no `nextCursor`. No Airtable create/update/delete operations were called.

The graph-relevant projection (`Record ID`, `Parent Record ID`) was refreshed in `reference-model/pilot-snapshot.mjs`. `buildSourceReadEvidenceManifest` computes a SHA-256 digest over a canonical projection sorted by record ID, records the read identity, returned count, reported total, pagination completion, scope, and algorithm label, and passes that manifest to the consumer gate. Tests confirm reordering does not change the digest and changing a parent reference does. The gated consumer accepts the snapshot with 10 vertices, 8 parent edges, and `NO_CYCLE`.

**Digest scope limitation:** the test digest covers only the graph-relevant projection, not all Airtable fields or the entire source response. The source-read ID is a descriptive identifier, not an authenticated connector receipt. The completeness flag is an explicit test attestation based on the observed record count and absent pagination cursor; it is not cryptographic proof of exhaustiveness.

## Findings and disposition

1. The former raw exports from the public resolver facade have been removed. An architecture test rejects production imports of raw analyzer names and checks that the public facade does not mention them.
2. The gated consumer owns the analyzer rather than accepting an arbitrary callback, closing the earlier callback-substitution bypass in that entry point.
3. The internal module remains technically importable by any module that deliberately names it. The guard is a CI architecture rule, not a language-enforced security boundary.
4. The source snapshot flow passes in CI, but it runs against a checked-in read-only snapshot refreshed from Airtable, not a live Airtable call during CI. It demonstrates the artifact/consumer path, not continuous source freshness or a production connector integration.
5. Lean remains a separate formal layer. Successful compilation does not establish JS/Lean semantic equivalence, digest verification inside Lean, or completeness of the source.
6. No Airtable data was mutated during this test. No production backend or production deployment is adopted.

## Verification

- Resolver tests, including the source-read evidence manifest and read-only pilot graph flow: [CI run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40493248134)
- Lean build: [CI run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40493248339)

Repeat this audit after architectural changes and before any production integration.


## Follow-up: structured source-read evidence manifest (2026-10-27)

The consumer gate now requires a structured `yggdrasil-source-read-evidence-v1` manifest rather than a bare `sourceCompletenessAttested` boolean. The manifest checks source-read identity and digest equality, returned-record count against the artifact binding count, reported total against returned count, pagination completion, a non-empty declared source scope, and the digest algorithm label.

This is a stronger consistency contract and the tests exercise mismatched counts, incomplete pagination, blank scope, and digest mismatch. It is **not yet an independent proof of source completeness or authenticity**: a caller can still fabricate a self-consistent manifest unless a trusted source adapter creates and protects it. The checked-in snapshot test remains fixture-based; CI does not query Airtable live. Next step is to define a trusted adapter boundary and evidence provenance without mutating the pilot.


## Trusted-adapter boundary — 2026-10-27

Added `reference-model/source-read-adapter.mjs` with `adaptCompleteSourceRead`. It accepts a connector response contract containing the source read ID, explicit scope, returned records, reported total record count, and next cursor. It rejects malformed responses, non-null pagination cursors, and count mismatches before producing a graph certificate and evidence manifest. The accompanying tests cover a successful complete response and those rejection paths.

**Boundary limitation:** this adapter validates the response it receives; it cannot authenticate the connector or independently prove that a caller supplied the unmodified connector response. A production integration must call this adapter directly at the connector boundary and preserve provenance rather than reconstructing the response object from user-provided fields. This is reference-model code, not production enforcement.

Verification for the adapter and its tests:
- Resolver tests: [CI run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40493756631)
- Lean build: [CI run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40493750759)


## Source-adapter boundary update

`reference-model/source-read-adapter.mjs` now exports `analyzeCompleteSourceRead(read)` as the end-to-end reference entry point. It calls `adaptCompleteSourceRead`, then `analyzeVerifiedGraphInput`, and returns scope/count metadata only after the gate accepts the artifact. Tests cover a complete acyclic read and verify that a non-null next-page cursor is rejected before analysis.

This reduces caller-side assembly of manifests and artifacts, but it is **not a security boundary by itself**: any code with access to the function can still pass a fabricated object. Production integration must invoke this function directly on the actual connector response at the trusted connector boundary, with source identity/scope established by that integration. The current tests are deterministic reference-model tests, not a live connector integration.


## Airtable response-shape adapter

`reference-model/source-read-adapter.mjs` now exports `analyzeAirtableTableResponse(response, config)`. It maps the connector's `records[].cellValuesByFieldId`, `metadata.totalRecordCount`, and `nextCursor` fields into the existing gated source-read path. The field IDs and table/base IDs are supplied explicitly from the Airtable schema; the adapter rejects missing application record IDs and forwards pagination/count validation to the source-read gate.

A read-only connector query was performed against base `appaUa8VbUJZmzmxe`, table `tblvy8J5UC6Y6SylE`, using the schema's Record ID and Parent Record ID field IDs. The connector returned 10 records, reported total count 10, and returned no next cursor. The recorded-value test exercises the same response shape and confirms 10 graph vertices, 8 parent edges, and `NO_CYCLE`; a separate test rejects a non-null cursor.

**Evidence boundary:** this is a real read-only connector observation plus a deterministic recorded-value test, not a live Airtable call executed by CI. The test's Airtable-shaped response is constructed from the observed values; it does not preserve a signed or connector-issued receipt. Source authenticity remains a trusted-integration responsibility.


## Identity preservation correction

A follow-up review found that the gated consumer converted numeric vertex IDs to strings for cycle analysis. That could preserve the cycle/no-cycle result while losing the original source record identity in the analysis projection. The gate now returns its verified source-vertex bindings, and the consumer reconstructs parent references through those bindings before running `analyzeParentGraph`. The Airtable-shaped test now asserts that `AF-PILOT-010` retains parent `AF-PILOT-009` in the analysis records.

This change preserves application-level source IDs across the numeric graph representation; it does not authenticate the source response. CI for the identity-preservation commits is pending.
