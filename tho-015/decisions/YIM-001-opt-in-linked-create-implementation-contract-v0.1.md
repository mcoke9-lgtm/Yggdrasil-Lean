# YIM-001 Opt-in Linked Create — Frozen Implementation Contract

**Artifact:** YIM-001-LCC-IC-001  
**Version:** 0.1  
**Status:** Frozen at design level; implementation not authorized by this record  
**Date:** 2026-11-11  
**Normative location:** `yim-001/WRITE-GATING-SPEC.md`, Section 8.8  
**Related adopted decisions:** `tho-015/decisions/YIM-001-create-provenance-integration-decisions-v0.1.md`

## 1. Purpose and authority

This record packages the exact implementation contracts established under the six adopted linked-create design decisions. The normative requirements are in YIM-001-WGS Section 8.8; this record is a concise engineering reference.

This freezes design details only. It does not authorize source changes, tests, recovery implementation, or changes outside opt-in Core Memory create.

## 2. Event contract

The existing provenance envelope is retained. Its `actor_id` field carries the exact caller-supplied actor claim; it is not authentication.

The ledger event registry must add `record.create_intent`. Existing `record.created` events remain valid and retain their prior meaning unless their details identify the linked-completion schema.

Intent details use the exact schema:

- `schema = "YIM-001-LCC-INTENT/1"`
- `operation_id`: canonical UUIDv4
- `record_digest`: 64 lowercase hexadecimal SHA-256 characters

Completion details use the exact schema:

- `schema = "YIM-001-LCC-COMPLETION/1"`
- `operation_id`: same UUIDv4 as intent
- `intent_event_id`: exact intent event ID
- `record_digest`: same digest as intent

Both events carry the same top-level `actor_id`, `record_id`, and `version_id`. Event ID, record ID, version ID, and operation ID remain distinct.

## 3. Canonical digest

- Profile: `YIM-001-LCC-JCS/1`
- Envelope has exactly `record_id`, `version_id`, `parents`, `content`, `metadata`.
- `parents` is `[]`.
- Canonicalization: JSON Canonicalization Scheme (JCS), RFC 8785.
- Encoding: UTF-8, no BOM, no trailing newline.
- Reject non-finite numbers, unpaired UTF-16 surrogates, and values outside JCS/IEEE-754 number rules; do not round or normalize.
- No Unicode normalization.
- Digest input: ASCII bytes `YIM-001-LCC-INITIAL-VERSION-DIGEST\0v1\0` followed by the canonical JCS bytes.
- Digest: SHA-256, rendered as 64 lowercase hexadecimal characters.

Event hash-chain digest is a separate value and is not the record digest.

## 4. Operation ID and invocation

Linked mode requires:

`create STORE CONTENT --ledger LEDGER --actor ACTOR_ID --operation-id UUID`

The operation ID is caller-supplied canonical lowercase UUIDv4 text. The caller generates it once, retains it, and reuses it for retries. A new request uses a new UUID.

Candidate record/version IDs are generated before intent append and stored in the intent's top-level event fields. On retry, the validated ledger is searched by operation ID before candidate IDs are generated; the original candidate IDs and digest are recovered from the intent. The retry compares the supplied actor/content/metadata against the existing intent.

There may be at most one intent and one completion event per operation ID. Duplicate operation evidence is rejected as ambiguous/contradictory; it is never continued by appending another event.

## 5. Outcome vocabulary

| Outcome | Meaning |
|---|---|
| `NOT_FOUND` | No intent or completion for the operation ID in a valid ledger. |
| `INCOMPLETE` | Unique valid intent exists; candidate record absent; exact operation may resume. |
| `CONSISTENT` | One matching intent, committed record, and linked completion; all identifiers, actor claims, and digests agree. |
| `UNCERTAIN` | Required history missing/malformed/unreadable, indeterminate read-back/commit, or matching record exists without completion. |
| `CONFLICT` | Direct contradiction: actor, identity, digest mismatch, or completion without matching intent/record. |
| `AMBIGUOUS` | Multiple candidate intent/completion associations cannot be uniquely resolved after complete-history validation. |

Malformed/unreadable complete history yields `UNCERTAIN` before duplicate ambiguity. The unresolved direct-conflict versus malformed-history precedence remains unchanged.

The linked-create command returns ordinary success and exit code 0 only for `CONSISTENT`. Machine-readable results contain `outcome`, `operation_id`, `findings`, and relevant event IDs.

## 6. Fault-injection contract

Tests inject failures through internal seams/mocks at:

1. before and after intent append;
2. intent read-back;
3. before, during, and after memory snapshot replacement;
4. memory read-back;
5. before and after completion append;
6. completion read-back.

Fault injection is test-only, with no user-accessible CLI/environment bypass. Tests assert actual file state/bytes after each fault. A raised write error after replacement must be treated as potentially committed until read-back resolves it.

## 7. Verified implementation dependencies

Source inspection on branch `tho-015/probe1-ci-bootstrap` established:

- `ProvenanceEvent` already has top-level `actor_id`, `record_id`, and `version_id`, plus JSON `details`.
- The current event-type set includes `record.created` but not `record.create_intent`; the registry and strict event validation need a bounded source update.
- Current `MemoryStore.create_record()` generates UUIDv4 record/version IDs internally. Linked implementation must expose or stage the exact candidate so its identifiers/digest can be written to intent before persistence.
- The current `reconcile-create` adapter is creation-event-oriented and must be extended or complemented to reconcile intent/completion/record triples without mutating either file.
- The current storage helper uses atomic replacement for each individual file only; no cross-file transaction is available.

## 8. Implementation boundary and open constraints

- Existing unlinked `create` remains unchanged.
- Linked create is opt-in and requires explicit ledger, actor claim, and operation ID.
- No cross-file atomicity is claimed.
- Recovery remains separately specified and authorized; this contract does not authorize recovery implementation.
- No revise/annotate-conflict/import/export instrumentation is included.
- MRP-001 Revision 4 remains unchanged.
- Direct-conflict versus malformed-history precedence remains unresolved.
- Source code and tests are unchanged by this contract-freeze pass.

## 9. Disposition

The five previously outstanding contract areas—event schemas, canonical digest, operation-ID/CLI behavior, outcome vocabulary, and fault-injection seams—are now frozen at design level in YIM-001-WGS Section 8.8. Implementation and conformance execution remain pending separate authorization.
