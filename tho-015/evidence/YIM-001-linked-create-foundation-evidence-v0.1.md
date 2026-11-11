# YIM-001 Linked-Create Foundation — Test Evidence

**Artifact:** YIM-001-LCC-FOUNDATION-EVIDENCE-001  
**Version:** 0.1  
**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Status:** Tested and CI-verified for the bounded foundation scope

## Scope executed

Implemented only the independent linked-create foundation:

- Added `record.create_intent` to the provenance event registry.
- Added strict intent-event schema validation.
- Added strict linked-completion schema validation for `record.created` events using `YIM-001-LCC-COMPLETION/1`.
- Preserved legacy `record.created` events that do not declare the linked-completion schema.
- Added RFC 8785 JCS canonicalization through pinned dependency `rfc8785==0.1.4`.
- Added `YIM-001-LCC-JCS/1` initial-version digest helper with the exact NUL-separated domain prefix and SHA-256 output.
- Added independent tests for canonical bytes, ECMAScript number serialization, invalid JCS values, fixed digest vector, digest field binding, intent/completion schema acceptance, legacy compatibility, and invalid event details.
- Updated CI to install the pinned dependency before running tests.

## Source and test commits

- Event registry, schema validation, digest helper: `1615024fd870e707f653dd7d08fc3c986bf818cf`
- Digest prefix correction: `062a7e7b04890c115b232185f55c7acfe1f112c7`
- Foundation tests: `17a4be6439d61fdd7afd19f3e89a59c674ff3891`
- Pinned dependency: `b9c4e49215296b63489245f1a596ee070c431ca3`
- CI dependency installation: `88701eea2a66c187f09c850e2b80ac6cf8e206dd`
- Final source prefix correction: `c568b9a81182b7dddbeb64a30a841e1a63f48b86`

## Verification

- Core Memory System CI run **43025051341**: success.
  - 89 unit tests passed.
  - Portable package built and verified.
  - Package SHA-256: `058b8b0e4877f1a425cc899f5996f1a718c88337f102097540eebc78a20aee87`.
  - [Workflow run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025051341)
- Lean build CI run **43025051027**: success.
  - [Workflow run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025051027)

## Scope limits and non-claims

- This does not implement linked-create orchestration or alter the existing unlinked `create` path.
- No intent → memory commit → completion persistence sequence was implemented.
- No retry or partial-state reconciliation behavior was implemented.
- No recovery behavior was implemented.
- No cross-file atomicity is claimed.
- This foundation test pass does not establish full LCC-01 through LCC-08 conformance.
- MRP-001 Revision 4 remains unchanged.
- Direct-conflict versus malformed-history precedence remains unresolved.

## Disposition

The independent event-schema and canonical-digest foundation is **tested and CI-verified**. The next implementation stage remains the separately bounded linked-create persistence sequence and must preserve the adopted opt-in, retry, recovery, and atomicity boundaries.
