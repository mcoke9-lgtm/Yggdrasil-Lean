# YIM-001 Decision 6 — Write-Gating Scope Adoption

**Decision:** YIM-001-MHCP-001-D6S  
**Record version:** 0.2  
**Decision date:** 2026-11-11  
**Status:** Adopted at the decision level; normative specification update pending  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Supersedes:** The proposed-only status of S2 in `YIM-001-MHCP-001-decision-6-write-scope-options-v0.1.md`; does not supersede Decision 6 A+B

## 1. Decision

The user approved **Option S2** as the intended scope of Decision 6 B:

> Before any ordinary state-changing YIM-001 operation, validate every authoritative history required to establish that operation's safe preconditions. If a required history is malformed or unreadable, reject the operation as UNCERTAIN.

The scope includes, at minimum:
- Core Memory `create`;
- Core Memory `revise`;
- Core Memory `annotate-conflict`;
- provenance `event-append`;
- future import, export, and recovery operations when they mutate authoritative state or append history.

The scope is not limited to provenance-ledger append operations.

## 2. Preserved access and recovery boundaries

- Read-only inspection, verification, and reconciliation remain available, consistent with the separately adopted Decision 5 read-only reconciliation path.
- Recovery and repair require a separately defined, explicit, evidence-preserving pathway. They are not ordinary writes and must not silently normalize, overwrite, or discard malformed history.
- The complete required history must be validated before an ordinary state mutation or subsequent event append.
- If a required history is malformed or unreadable, the ordinary write is blocked and the history condition is reported as `UNCERTAIN`.
- An operation whose commit status is unknown remains distinct from a validation failure; existing identity and retry rules apply.
- This decision does not itself require cross-file atomic transactions or claim that the current implementation provides them.

## 3. Required specification work

The normative update must make explicit:
1. the covered ordinary write operations, including future mutation operations;
2. which authoritative history or histories each operation must validate;
3. the validation boundary and fail-closed `UNCERTAIN` result;
4. continued availability of read-only diagnosis;
5. the separate recovery/repair pathway and evidence-preservation requirements;
6. the distinction between validation failure and uncertain commit outcome;
7. validation of complete required history before mutation.

Where an operation has no defined authoritative provenance history, the specification must define the required history boundary rather than assume a ledger exists or is authoritative.

## 4. Relationship to existing decisions

Decision 6 remains A+B:
- **A:** reject malformed events during reconciliation;
- **B:** reject malformed history before permitting subsequent writes.

Malformed event history remains classified `UNCERTAIN`. Strict integer typing remains required.

The previously recovered Interaction 3 precedence decision remains unchanged: validate the complete history first; any malformed event anywhere yields `UNCERTAIN`, including when unrelated to the requested record or when duplicate matching events also exist. The separate direct `CONFLICT` versus malformed-history `UNCERTAIN` precedence question remains unresolved.

## 5. Status and change control

- S2 write-gating scope: **adopted at decision level**.
- Decision 6 A+B: **adopted at decision level**.
- Normative YIM-001 specification update: **pending; not changed by this record**.
- Source implementation: **unchanged**.
- Tests: **unchanged**.
- Cross-file atomic transaction: **not required or claimed by this decision**.
- Recovery/repair details: **must be specified before implementation**.

This is an append-only decision record. It does not imply that the implementation already satisfies the adopted requirement.
