# YIM-001 Decision 6 — Write-Gating Scope Recovery

**Artifact:** YIM-001-MHCP-001-D6R  
**Version:** 0.1  
**Status:** Recovered decision record with scope gap preserved  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Related proposal:** YIM-001-MHCP-001 v0.3 workstream split

## 1. Recovered interaction

Conversation recovery located the Decision 6 exchange at approximately 2026-11-11 04:36 UTC.

The issue presented was malformed event schema and hash linkage that could pass despite missing fields or type errors (including a boolean `version: true` being treated as matching integer `1`), potentially allowing subsequent writes.

The two selected requirements were:

- **A:** reject malformed events during reconciliation;
- **B:** reject malformed history before permitting subsequent writes.

The user selected **A+B** and classified malformed event history as **UNCERTAIN**, pending a specification update.

The contemporaneous assistant record further states:
- malformed history is rejected before permitting subsequent writes;
- strict integer typing is required;
- rationale: history cannot be reliably interpreted or trusted, but malformed history does not automatically establish a conflict.

## 2. Scope explicitly not specified

The recovered Decision 6 record does **not** enumerate which write operations are covered by B. In particular, it does not specify whether B applies to:
- provenance-ledger append operations only;
- Core Memory `create`, `revise`, and `annotate-conflict`;
- all write-capable operations across both stores; or
- another defined set of operations.

No narrower or broader scope is inferred here. The phrase “before permitting subsequent writes” remains the adopted high-level requirement; its operation boundary is unresolved.

## 3. Adjacent implementation boundary evidence

The YIM-001 portable CLI PR #53 describes the provenance ledger as separate from memory snapshots and explicitly says store operations are not yet automatically instrumented. Its documentation also states that events describe actions and do not themselves cause or authorize memory mutations.

This is useful implementation context, not a later amendment to Decision 6. It does not resolve the intended scope of B.

Reference: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/pull/53

## 4. Effect on corrective workstream B

Workstream B cannot be reduced to “validate before event-append” based on the recovered decision. Nor can it be expanded to all Core Memory writes without a scope resolution.

Before implementation, the specification must identify:
1. the complete set of write operations covered by B;
2. the authoritative complete-history validation boundary for each covered operation;
3. the fail-closed behavior when history is malformed, unreadable, or absent;
4. how the read-only reconciliation path remains distinct from write-capable access, consistent with the earlier Decision 5.

Until those points are resolved, no code change or test expectation should assume a particular operation set.

## 5. Status and change control

- Decision 6 A+B: **recovered as selected**.
- Malformed history classification as `UNCERTAIN`: **recovered**.
- Strict integer typing: **recovered**.
- Exact write-operation scope: **not specified in recovered decision**.
- Normative specification update: **not made**.
- Implementation or test changes: **not authorized or made by this recovery**.
- Direct `CONFLICT` versus malformed-history `UNCERTAIN` precedence: remains separately unresolved under Interaction 3 recovery.

This artifact records historical decision evidence only. It does not amend or reinterpret the adopted decision.
