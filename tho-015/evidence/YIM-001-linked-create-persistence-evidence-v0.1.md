# YIM-001 Linked-Create Persistence — Implementation Evidence v0.1

**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Scope:** Opt-in linked-create persistence sequence only  
**Normative contract:** `yim-001/WRITE-GATING-SPEC.md` Section 8.8  
**Decision/reference:** `tho-015/decisions/YIM-001-opt-in-linked-create-implementation-contract-v0.1.md`

## Implemented

- Added `yim-001/src/yim001/linked_create.py` with an opt-in linked-create operation.
- Added CLI command `linked-create STORE CONTENT --ledger PATH --actor ACTOR_ID --operation-id UUID [--metadata PATH]`. Existing unlinked `create` remains a separate command and retains its original behavior.
- The linked path validates the complete Core Memory snapshot and complete provenance ledger before mutation.
- A new operation generates distinct candidate record/version/event identities, computes the frozen JCS digest, appends and reads back one intent, commits the exact candidate record, reads it back and verifies its digest, then appends and reads back one linked completion.
- Retry reuses the intent's candidate IDs and digest. Intent-only state can resume. A matching committed record without completion returns `UNCERTAIN` and is not silently completed. A completed matching operation returns `CONSISTENT` without another record or event.
- Actor mismatch and candidate-content/metadata mismatch return `CONFLICT`.
- Added internal fault callback points around intent, memory, and completion writes. No user-facing fault-injection option was added.

## Verification

- Core Memory System CI: [run #43025937816](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025937816) — **passed, 96 tests**.
- Lean build: [run #43025937848](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025937848) — **passed**.
- Earlier intermediate run #43025742269 also passed 96 tests before the final identifier-distinctness hardening; final run above covers the final source commit.
- Tests cover successful persistence, intent/record/completion linkage, completed-operation idempotency, intent-only retry, record-without-completion uncertainty/no silent repair, actor mismatch conflict, CLI invocation/retry, and malformed-ledger preflight with byte-for-byte no-mutation assertions.

## Commits

- Linked-create protocol module: `05f7d4480719dd1316fe30451e47177058955237`
- CLI integration: `b909ebc0d91c3634926cd98447c8004b11da547d`
- Initial persistence/retry tests: `c4cec26777ade1cd1d17271966bf396e8dd0f7fb`
- CLI and preflight tests: `1b7158b803e9991979947e49c42116165eea4dad`
- Cross-entity identifier distinctness hardening: `d45d38ac91175bd83e009c0d80259291da528e66`

## Explicit limits and remaining conformance work

- This is an initial implementation checkpoint, not full LCC-01 through LCC-08 conformance.
- The full fault matrix is not yet covered: injected failures at each before/after append, read-back, and during snapshot replacement still need dedicated tests. The current tests exercise representative post-intent and post-memory interruption states.
- Dedicated tests for every completion/intent contradiction, duplicate-operation ambiguity, operation-ID reuse with changed content, and all malformed/unreadable Core Memory and ledger combinations remain outstanding.
- A post-replacement exception may leave a committed state; the command reports an unresolved error and the next invocation reconciles from persisted files. No cross-file atomicity is claimed.
- Recovery is not implemented or authorized.
- The existing unlinked create path remains unchanged. MRP-001 Revision 4 remains unchanged. Direct-conflict versus malformed-history precedence remains unresolved.
