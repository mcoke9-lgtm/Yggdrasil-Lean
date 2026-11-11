# YIM-001-MHCP-001 — Corrective Workstream Split

**Artifact:** YIM-001-MHCP-001  
**Version:** 0.3  
**Status:** Proposed workstream decomposition — not adopted  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Supersedes:** No prior version; supplements v0.1 and v0.2 addendum

## 1. Purpose

Separate the confirmed reconciliation-precedence defect from the broader integration and write-gating questions. This prevents a narrow function correction from being mistaken for a complete implementation of the adopted history-validation decisions.

## 2. Workstream A — Function-level malformed-history precedence

### Confirmed requirement

The recovered Interaction 3, Option A decision requires complete-history validation before record-specific outcome calculation and establishes:

- malformed history yields `UNCERTAIN`;
- `UNCERTAIN` takes precedence over `AMBIGUOUS`;
- a malformed event anywhere in the history counts, even if unrelated to the requested record.

### Observed mismatch

The inspected `reconcile_creation_event()` checks direct evidence conflicts and duplicate evidence before its malformed/unreadable-history branch. The THO-015 Probe 2 harness supplied `history_state="malformed"` together with duplicate matching evidence and observed `AMBIGUOUS`, contrary to the adopted precedence decision.

### Proposed bounded correction

A future authorized source change should ensure that, when the supplied/validated history state is malformed or unreadable, the function returns `UNCERTAIN` before duplicate/ambiguity classification. This workstream must not silently decide the unresolved direct-`CONFLICT` versus `UNCERTAIN` pair.

Required regression cases:
1. malformed history + duplicate matching evidence → `UNCERTAIN`;
2. malformed unrelated event + otherwise matching target evidence → `UNCERTAIN`;
3. unreadable history + duplicate evidence → `UNCERTAIN`;
4. valid history + duplicate evidence → retain existing `AMBIGUOUS` behavior;
5. valid history + direct evidence conflict → retain existing `CONFLICT` behavior;
6. malformed history + direct evidence conflict → explicitly marked unresolved pending decision; do not encode an assumed expected outcome.

## 3. Workstream B — Complete-history integration and write gating

### Recovered Decision 6

The user selected **A+B** for malformed event schema:
- **A:** reject malformed events during reconciliation;
- **B:** reject malformed history before allowing subsequent writes;
- malformed history is classified as `UNCERTAIN`.

The recoverable decision history records the phrase “before permitting subsequent writes.” It does not define the exact set of writes covered. No retrieved decision text establishes whether this means only provenance-ledger appends or also Core Memory `create`, `revise`, and `annotate-conflict` operations.

### Observed architecture

- Provenance CLI `event-append` loads and validates the complete event ledger before appending to that ledger.
- Core Memory CLI `create`, `revise`, and `annotate-conflict` load and mutate the memory snapshot without loading the separate provenance ledger.
- No production caller connecting `EventLedger.from_snapshot()` to `reconcile_creation_event()` was found in the inspected Python module/test tree.

### Required clarification before implementation

The scope of “subsequent writes” must be recovered from the original decision record or resolved explicitly by the user. Until then:
- do not claim that memory-store write gating is adopted;
- do not assume provenance-ledger append is the only covered write;
- do not change CLI behavior;
- preserve the distinction between validating the ledger before appending to the ledger and validating it before changing Core Memory.

### Proposed integration design questions

After scope recovery, specify one explicit trusted boundary that validates the complete ledger before record-specific reconciliation. Define how the validated-history result is carried to reconciliation, how malformed/unreadable history is represented as `UNCERTAIN`, and which write operations are gated. Keep the read-only reconciliation path distinct from write-capable paths, consistent with the user's earlier Decision 5.

## 4. Open decision — malformed history versus direct CONFLICT

Interaction 3 explicitly resolves `UNCERTAIN` over `AMBIGUOUS`, but the recovered wording does not independently resolve `UNCERTAIN` versus a direct `CONFLICT`. Current source ordering is not user authorization.

This pair remains an explicit open decision. No implementation or test expectation should settle it by implication.

## 5. Change-control boundary

This document is a proposal only. It does not:
- adopt either workstream;
- authorize source or test changes;
- revise the normative YIM-001 specification;
- settle the direct-conflict precedence question;
- settle the scope of Decision 6 write gating;
- alter the separate THO-015 Probe 1 idempotency track.

No source, tests, specification, adopted decision, or production branch has been changed by this proposal.
