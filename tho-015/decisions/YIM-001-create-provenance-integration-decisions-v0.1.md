# YIM-001 Create-to-Provenance Integration Design Decisions

**Version:** 0.1  
**Status:** Decisions adopted; specification and implementation updates pending  
**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Related proposal:** `tho-015/proposals/YIM-001-create-provenance-integration-design-v0.1.md`

## Scope

This record captures the user's six decision-level selections for the proposed linked-provenance protocol for Core Memory `create`. It does not itself amend the adopted write-gating specification, authorize source or test changes, or authorize recovery implementation.

## Adopted decisions

### D1 — Partial-commit protocol

Adopt the explicit intent/completion protocol as the design direction:

1. Validate the complete existing Core Memory snapshot and complete provenance ledger before authoritative mutation.
2. Append and verify `record.create_intent`.
3. Persist the exact candidate Core Memory record.
4. Append and verify `record.created`.
5. Report success only after both persisted states are read back and verified.

This protocol makes partial progress observable. It does not provide cross-file atomicity.

### D2 — Ledger integration

Linked creation is opt-in. Existing `create` behavior remains unchanged. Linked creation requires an explicit ledger argument. Making ledger integration mandatory requires a later explicit specification amendment.

### D3 — Canonical record digest

Adopt SHA-256 over a versioned canonical JSON representation of the initial version envelope, including:

- record ID;
- version ID;
- empty parent list;
- content; and
- metadata.

The canonical representation uses UTF-8, sorted object keys, deterministic separators, and a documented digest-domain prefix. Nondeterministic fields are excluded unless explicitly specified. The exact byte-level serialization remains to be specified and tested.

### D4 — Operation ID and retry identity

Adopt a stable operation ID generated once per create attempt and persisted before the first intent append. It is distinct from event, record, and version IDs.

A retry reuses the original operation ID and must locate and reconcile the existing intent before proceeding. Reuse with different candidate data or identifiers produces conflict/uncertainty, never a second creation. A new operation ID represents a new create request.

### D5 — Actor claim

Linked creation requires an explicit caller-supplied actor ID. The exact supplied value is preserved in both events and treated only as a claimed actor, not an authenticated identity. The environment must not be used to infer identity. Missing actor ID blocks linked creation before mutation.

### D6 — Ordinary retry versus recovery

Adopt the following boundary:

1. If an intent exists and the record is absent, ordinary retry may resume the exact candidate only when its identity and digest match.
2. If the record exists and matches the intent but completion is absent, report `UNCERTAIN`; ordinary retry must not silently append completion.
3. If completion exists and matches the record, return the already-completed result without creating another record.
4. Contradictory IDs, digest, actor claim, or event history stop processing with `UNCERTAIN` or the applicable conflict outcome.
5. Recovery is separate, explicitly authorized, evidence-preserving, and must append its own evidence. It must not silently rewrite or delete original history.

## Outstanding specification and implementation work

The following remain required before implementation can be considered conformant:

- Specify the exact event schemas and required fields for `record.create_intent` and `record.created`, including the intent-event reference from completion.
- Specify the canonical serialization bytes, digest-domain prefix, and digest version.
- Define operation-ID generation, storage, uniqueness enforcement, and deterministic lookup.
- Define CLI argument syntax and validation for explicit ledger and actor claim.
- Define precise outcome/finding classifications for every interrupted state.
- Add reconciliation support for intent-only, matching completion, missing completion, and contradictory evidence.
- Add failure-injection and no-mutation/partial-state tests at every persistence boundary.
- Amend the adopted operation-to-history specification only as needed to define the opt-in linked-create mode; do not silently make the ledger mandatory.
- Preserve the existing no-cross-file-atomicity boundary and the separately authorized recovery boundary.

## Explicit non-decisions and constraints

- This record does not authorize source-code, test, or normative specification changes.
- This record does not authorize recovery implementation.
- No cross-file transaction or atomicity is claimed.
- Actor claims are not authentication.
- Direct `CONFLICT` versus malformed-history `UNCERTAIN` precedence remains unresolved.
- MRP-001 Revision 4 remains unchanged.
- Existing unlinked `create` behavior remains unchanged pending the required specification and implementation work.

## Disposition

All six design decisions above are **adopted at the decision level**. The design is not yet implemented or fully specified. Further work must proceed within the scope and constraints recorded here.
