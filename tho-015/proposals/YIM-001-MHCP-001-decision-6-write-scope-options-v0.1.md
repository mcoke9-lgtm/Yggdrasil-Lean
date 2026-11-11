# YIM-001-MHCP-001 — Decision 6 Write-Gating Scope Options

**Artifact:** YIM-001-MHCP-001-D6S  
**Version:** 0.1  
**Status:** Proposed scope clarification; no option adopted  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Basis:** Recovered Decision 6 A+B; YIM-001 implementation and CLI boundaries inspected on this branch

## 1. Existing decision preserved

Decision 6 remains:

- **A:** Reject malformed events during reconciliation.
- **B:** Reject malformed history before permitting subsequent writes.
- Malformed event history is classified **UNCERTAIN**.
- Strict integer typing is required.

This document does not change that decision. It proposes explicit operation boundaries for the unresolved phrase “subsequent writes.”

## 2. Relevant current implementation boundaries

The inspected YIM-001 CLI has two separate persisted objects:

1. Core Memory snapshot, mutated by `create`, `revise`, and `annotate-conflict`.
2. Provenance event ledger, mutated by `event-append`.

The memory-store CLI commands load and validate the memory snapshot but do not load or validate the separate provenance ledger. The event CLI commands validate the complete provenance ledger before appending to that ledger. The project status and PR #53 explicitly state that memory-store operations are not yet automatically instrumented by provenance events.

Therefore, “validate before event-append” is already a narrower, ledger-local behavior in the current implementation. It does not establish that the broader Decision 6 requirement is satisfied.

## 3. Scope options

### Option S1 — Ledger-local gate

Before any provenance-ledger append, validate the complete ledger. If malformed or unreadable, reject the append as UNCERTAIN.

Covered:
- `event-append`.

Not covered:
- Core Memory `create`, `revise`, `annotate-conflict`;
- import, export, recovery, or other state-changing operations.

**Effect:** Smallest implementation boundary. It protects the ledger from appending onto an invalid history but allows memory state to continue changing while the separate provenance history is malformed.

### Option S2 — All ordinary state-changing YIM-001 operations

Before any ordinary state-changing operation, validate every authoritative history required to establish the operation's safe preconditions. If a required history is malformed or unreadable, reject the operation as UNCERTAIN.

Covered at minimum:
- Core Memory `create`, `revise`, `annotate-conflict`;
- provenance `event-append`;
- future import, export, and recovery operations when they mutate authoritative state or append history.

Read-only operations remain available, including inspection, verification, and the separate read-only reconciliation path.

A narrowly defined recovery/repair path would need its own contract. It must not silently append to, rewrite, or erase malformed history; it should preserve the original bytes/evidence and record any repair as a distinct, reviewable action where possible.

**Effect:** Prevents ordinary state changes from proceeding on top of untrusted required history, while preserving read-only diagnosis and a separately specified recovery route.

### Option S3 — Cross-object transactional gate

Before any mutation to either the Core Memory snapshot or provenance ledger, validate both complete objects and require the memory mutation and its corresponding provenance event to commit as one coordinated operation. If either history is malformed, unreadable, or the coordinated commit cannot be established, do not report success; resolve uncertain commit outcomes before retrying.

Covered:
- All S2 operations;
- coupled memory-plus-provenance mutations as one logical transaction.

**Effect:** Strongest consistency model, but it requires transaction/receipt/retry semantics across two currently separate files. Current implementation does not provide this. It is a larger architecture change, not a small validation patch.

## 4. Recommendation for deliberation

Evaluate **S2** as the default scope for Decision 6 B.

Reason:
- S1 protects only one ledger append path and leaves Core Memory writes independent of malformed required history.
- S2 gives “before permitting subsequent writes” a clear ordinary-operation meaning without falsely claiming cross-file atomicity.
- S3 may ultimately be desirable for integrated provenance, but it adds a transaction guarantee not present in the recovered decision and not currently implemented.
- Read-only diagnosis should remain available. Recovery/repair must be separately defined so fail-closed behavior does not make evidence permanently inaccessible or encourage destructive repair.

This recommendation is not an adopted interpretation. The user must select or amend the scope before the specification is changed.

## 5. Required details if S2 is selected

The normative clarification should define:

1. **Covered writes:** every ordinary operation that changes authoritative Core Memory state or appends authoritative provenance history, including future mutation operations.
2. **Required histories:** which complete history or histories each operation must validate. Do not imply a provenance ledger exists or is authoritative for an operation until that relationship is specified.
3. **Failure result:** malformed or unreadable required history blocks the ordinary write and yields UNCERTAIN; preserve the underlying diagnostic evidence.
4. **Read-only access:** inspection, verification, and reconciliation remain available through read-only access, consistent with Decision 5.
5. **Recovery/repair:** separate, explicit, evidence-preserving pathway; not an ordinary write bypass and not permission to silently normalize or discard malformed history.
6. **Commit uncertainty:** distinguish validation failure from an operation whose commit status is unknown; apply the existing retry/identity rules rather than treating unknown as confirmed failure.
7. **Ordering:** validate the complete required history before making any state mutation or appending the next event.

## 6. Status and change control

- Decision 6 A+B: **recovered**.
- S1/S2/S3: **proposed scope options only**.
- Recommendation S2: **proposed, not adopted**.
- Normative specification: **unchanged**.
- Source and tests: **unchanged**.
- No transaction guarantee is claimed.
