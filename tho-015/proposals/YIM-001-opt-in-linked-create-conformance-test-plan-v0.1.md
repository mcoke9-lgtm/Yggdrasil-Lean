# YIM-001 Opt-in Linked Create — Bounded Conformance Test Plan

**Artifact:** YIM-001-LCC-CTP-001  
**Version:** 0.1  
**Status:** Adopted design translated into proposed test plan; tests not yet implemented or executed  
**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Normative authority:** Section 8 of `yim-001/WRITE-GATING-SPEC.md`  
**Decision authority:** `tho-015/decisions/YIM-001-create-provenance-integration-decisions-v0.1.md`

## 1. Purpose and scope

This plan defines a bounded conformance suite for the opt-in linked-provenance form of Core Memory `create`.

In scope: legacy unlinked-create compatibility; complete-history preflight; intent → record commit → completion ordering; canonical digest consistency; stable operation identity and retries; explicit actor-claim handling; partial-state reconciliation and uncertainty reporting; evidence preservation; and the no-cross-file-atomicity boundary.

Out of scope: automatic provenance instrumentation for revise, annotate-conflict, import, or export; mandatory ledger integration for unlinked create; recovery/repair implementation; cross-file transactions; changing MRP-001 Revision 4; and resolving direct-conflict versus malformed-history precedence.

No source or test changes are authorized by this plan. Test execution is pending a separate implementation authorization.

## 2. Preconditions before tests can be executable

The following contracts must be fixed before final expected-value assertions can be written:

1. Exact JSON schemas and schema identifiers for `record.create_intent` and `record.created`.
2. Exact canonical JSON profile, digest-domain prefix bytes, and stable test vectors.
3. Operation-ID generation and caller retry/surfacing contract.
4. CLI/API argument names and validation rules for explicit ledger path, actor claim, and operation ID.
5. Stable outcome/finding names for intent-only, record-committed-without-completion, completed, and contradictory states.
6. Deterministic fault-injection seams for ledger append, memory snapshot replacement, read-back verification, and completion append.

Tests must not invent these contracts.

## 3. Test groups

### LCC-01 — Legacy compatibility and opt-in boundary

- LCC-01.1: Existing unlinked `create` invocation succeeds without a ledger argument and retains its current output/store behavior.
- LCC-01.2: Unlinked `create` does not create or modify a provenance ledger implicitly.
- LCC-01.3: Linked mode without a ledger path is rejected before any store or ledger mutation.
- LCC-01.4: Linked mode without an actor claim is rejected before any store or ledger mutation.
- LCC-01.5: Explicit linked mode with a missing/unreadable ledger reports `UNCERTAIN` and leaves the Core Memory snapshot unchanged; linked create must not silently initialize a ledger.

### LCC-02 — Complete-history preflight

For every case, capture exact bytes of both files before invocation and assert no authoritative mutation:

- LCC-02.1: malformed Core Memory snapshot;
- LCC-02.2: unreadable Core Memory snapshot;
- LCC-02.3: malformed provenance event unrelated to the target record;
- LCC-02.4: wrong-typed sequence, including boolean;
- LCC-02.5: broken provenance hash-chain link;
- LCC-02.6: unreadable/truncated provenance ledger.

Every case must return machine-readable `UNCERTAIN`. Validation of both histories must finish before the first intent append.

### LCC-03 — Intent event contract and ordering

- LCC-03.1: Successful linked create appends exactly one `record.create_intent` before changing the memory snapshot.
- LCC-03.2: Intent contains schema ID, operation ID, claimed actor, candidate record/version IDs, and candidate digest.
- LCC-03.3: Intent append is read back and verified before memory persistence begins.
- LCC-03.4: Intent append failure leaves the Core Memory snapshot byte-for-byte unchanged.
- LCC-03.5: Intent append failure does not produce a completion event.
- LCC-03.6: Event ID, record ID, version ID, and operation ID remain distinct.

Use an instrumented persistence seam or deterministic fault hook to prove ordering; do not infer ordering only from final files.

### LCC-04 — Canonical digest conformance

- LCC-04.1: Fixed canonical-envelope test vectors produce the adopted SHA-256 digest.
- LCC-04.2: Reordered JSON object keys produce the same canonical bytes and digest.
- LCC-04.3: Changes to record ID, version ID, parent list, content, or metadata change the digest.
- LCC-04.4: Event generation and reconciliation use the same canonicalization profile.
- LCC-04.5: Domain prefix and canonicalization version are included exactly as specified.
- LCC-04.6: Excluded nondeterministic fields do not change the digest.
- LCC-04.7: Content-only digest fixtures are rejected as nonconformant.

### LCC-05 — Memory commit and completion

- LCC-05.1: Memory snapshot failure after a verified intent preserves the intent and leaves the original snapshot unchanged.
- LCC-05.2: No completion event is appended when memory commit fails.
- LCC-05.3: After memory commit, read-back confirms the exact candidate envelope and digest before completion append.
- LCC-05.4: Completion references the exact intent event ID.
- LCC-05.5: Intent and completion share operation ID, actor claim, record ID, version ID, and digest.
- LCC-05.6: Completion append failure preserves the committed record and intent; command reports `UNCERTAIN`, not ordinary success.
- LCC-05.7: A successful operation produces exactly one intent, one completion, and one matching committed record.
- LCC-05.8: Read-back verification failure at either file boundary reports `UNCERTAIN` and does not claim success.

### LCC-06 — Operation identity and retry

- LCC-06.1: Retry after intent append but before memory commit reuses the same operation ID and exact candidate identity/digest.
- LCC-06.2: A matching intent with absent record permits ordinary retry to resume only the exact candidate.
- LCC-06.3: Same operation ID with different candidate content/digest is rejected as conflict or `UNCERTAIN`; no second record or intent is created.
- LCC-06.4: Same operation ID with different record/version IDs is rejected.
- LCC-06.5: Same operation ID with a different actor claim is rejected.
- LCC-06.6: Duplicate intents for one operation ID are rejected or deterministically reconciled without ambiguous continuation.
- LCC-06.7: A new operation ID is treated as a new request and does not bind to an earlier operation.

### LCC-07 — Reconciliation and partial states

- LCC-07.1: Intent exists, record absent → classify as incomplete/unresolved; ordinary retry may resume only under LCC-06 matching conditions.
- LCC-07.2: Intent and matching record exist, completion absent → `UNCERTAIN`; ordinary retry must not append completion silently.
- LCC-07.3: Matching intent, record, and completion exist → return already-completed result without further mutation.
- LCC-07.4: Completion exists but record is absent → `UNCERTAIN` or applicable conflict; never claim success.
- LCC-07.5: Record digest or identifiers contradict intent → `UNCERTAIN` or applicable conflict; never overwrite the record to force agreement.
- LCC-07.6: Intent/completion actor claims differ → reject as contradictory evidence.
- LCC-07.7: Malformed unrelated ledger event blocks reconciliation with `UNCERTAIN`.
- LCC-07.8: Reconciliation is read-only; assert byte-for-byte unchanged memory snapshot and ledger.

### LCC-08 — Evidence preservation and atomicity boundary

- LCC-08.1: At each pre-mutation rejection, both files remain byte-for-byte unchanged.
- LCC-08.2: At each post-intent failure, original memory snapshot and appended intent are preserved exactly.
- LCC-08.3: At each post-memory/pre-completion failure, committed record and intent are preserved exactly.
- LCC-08.4: No test or CLI output describes the pair as a cross-file atomic transaction.
- LCC-08.5: No ordinary path deletes, rewrites, or normalizes original events to hide partial progress.
- LCC-08.6: No recovery/repair action is triggered by ordinary retry.

## 4. Execution order and bounded batches

1. **Contract freeze:** finalize event schemas, digest profile/test vectors, CLI arguments, operation-ID contract, and outcome vocabulary. No implementation yet.
2. **Pure contract tests:** canonical digest vectors, event schema validation, and operation-ID validation.
3. **Preflight + intent tests:** legacy compatibility, explicit opt-in, complete-history validation, intent ordering, and intent append failure.
4. **Memory commit + completion tests:** memory replacement fault, read-back checks, completion append failure, and successful linked create.
5. **Retry + reconciliation tests:** all partial states, duplicate operation IDs, contradictory evidence, and read-only invariance.
6. **Full verification:** focused suite, full YIM-001 suite, package/build checks, and review of persisted artifacts and diffs.

Stop a batch if its prerequisite contract is unresolved or observed behavior contradicts an adopted decision. Do not broaden into other Core Memory mutators.

## 5. Acceptance criteria

The linked-create implementation is conformant only when all LCC-01 through LCC-08 tests pass; legacy unlinked-create tests remain green; every partial-commit fault produces the specified preserved state and outcome; exact digest vectors pass; retry cannot duplicate a record or conceal unresolved partial work; reconciliation is read-only and validates complete required history; and focused/full CI plus relevant build workflows pass.

A green suite does not establish cross-file atomicity or authorize recovery.

## 6. Current status

- Six design decisions: adopted at decision level.
- Normative linked-create boundary: added to YIM-001-WGS Section 8.
- This conformance plan: recorded; tests not implemented or run.
- Source code: unchanged by this specification/test-plan pass.
- Test code: unchanged by this specification/test-plan pass.
- Recovery: not specified for implementation and not authorized.
- MRP-001 Revision 4: unchanged.
- Direct-conflict versus malformed-history precedence: unresolved and unchanged.


## 7. Contract freeze update — 2026-11-11

The six preconditions listed in Section 2 have now been frozen at design level by YIM-001-WGS Section 8.8 and decision/reference artifact `tho-015/decisions/YIM-001-opt-in-linked-create-implementation-contract-v0.1.md`.

The following exact contracts now govern test implementation:

- Intent event type `record.create_intent`, schema `YIM-001-LCC-INTENT/1`; completion details schema `YIM-001-LCC-COMPLETION/1`.
- Actor claim is the existing event-envelope `actor_id`, preserved exactly; not authenticated.
- Canonical digest profile `YIM-001-LCC-JCS/1`, RFC 8785 JCS over the five-member initial-version envelope, prefixed by ASCII `YIM-001-LCC-INITIAL-VERSION-DIGEST\0v1\0`, then SHA-256.
- Linked CLI requires `--ledger`, `--actor`, and caller-supplied canonical lowercase UUIDv4 `--operation-id`; the same ID is reused on retry.
- Outcomes are `NOT_FOUND`, `INCOMPLETE`, `CONSISTENT`, `UNCERTAIN`, `CONFLICT`, and `AMBIGUOUS`, with the meanings specified in WGS Section 8.8.4.
- Test-only internal fault seams cover intent append/read-back, memory replacement/read-back, completion append/read-back, including post-replacement exceptions.

Section 2 is historical as a list of prerequisites: these contracts are no longer open design questions. They are frozen but have not yet been implemented or tested. LCC-04 must include published JCS test vectors; the test implementation must use an RFC 8785-conformant canonicalizer and must not substitute ordinary `json.dumps(sort_keys=True)` as a claimed JCS implementation.

### Updated status

- Contract freeze: **completed at design level**.
- Normative specification Section 8.8: **saved and independently retrieved**.
- Implementation contract reference: **saved**.
- This test plan: **updated to reference the frozen contracts**.
- Source/tests: **unchanged in this pass**.
- Test execution: **not started**.
- Implementation authorization: **not granted by this record**.
