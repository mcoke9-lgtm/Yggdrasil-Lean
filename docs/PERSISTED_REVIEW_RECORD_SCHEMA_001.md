# Persisted Review Record Schema 001

**Status:** Draft schema proposal; not implemented or adopted  
**Parent contract:** [DURABLE_REVIEW_HISTORY_SPEC_001.md](DURABLE_REVIEW_HISTORY_SPEC_001.md)  
**Scope:** Serialization-neutral field inventory and mapping to current Lean model

## 1. Design rule

This schema describes the logical information a storage adapter must encode. It does not prescribe JSON, CBOR, SQL tables, file layout, canonical bytes, a digest algorithm, or a backend. Those choices remain open until the encoding and failure model are reviewed.

A stored record must distinguish the event that was originally assessed from any later reassessment. Do not use a mutable rule-set label as the sole identity of the rules.

## 2. Logical record

Each committed review event has the following logical sections.

| Section | Required fields | Purpose |
|---|---|---|
| Schema | `schema_name`, `schema_version` | Identify how to parse and migrate the record |
| Event envelope | `event_id`, `event_kind`, `project_id`, `branch_id`, `sequence`, `idempotency_key` | Stable identity, ordering, and retry handling |
| Lineage | `parent_event_ids`, `original_assessment_event_id` when reassessing | Preserve history and relationship to prior events |
| Transition | `before_state`, `after_state`, optional immutable state references | Retain both endpoints and their project/branch identity |
| Derivation | `change_set`, `derivation_name`, `derivation_version` | Preserve the recorded result and explain how it was derived |
| Rule snapshot | `rule_snapshot_id`, `routes`, optional canonical snapshot digest | Retain the actual route definitions, not only their label |
| Assessment | `identity_branch_review`, `route_decisions`, `review_required` | Preserve the assessment as originally made, including ordering |
| Integrity | `integrity_algorithm`, `integrity_value` or an explicitly defined equivalent | Detect accidental or unauthorized record changes |
| Adapter commit metadata (separate from event payload) | `commit_status`, backend receipt/reference when available | Record the storage outcome and evidence associated with the event; do not treat post-commit receipt metadata as part of the event payload being committed |

The schema must define whether each field is mandatory, conditionally mandatory, or explicitly unavailable. A missing value cannot be silently substituted with a default that changes meaning.

## 3. State and decision subrecords

### State

A `before_state` and `after_state` must preserve every current `ProjectState` field:

- `project_id`
- `branch_id`
- `state_version`
- `agreements` (ordered list)
- `evidence` (ordered list)
- `dependencies` (ordered list)
- `unresolved` (ordered list)

List ordering is part of the stored value unless a later reviewed specification explicitly defines a field as order-insensitive.

### Rule snapshot

Each route must preserve its `dependency_id` and ordered `watches` list. `rule_snapshot_id` is a human-readable identifier; the exact route payload and a snapshot digest (once its algorithm is chosen) establish which configuration was used.

### Route decision

Each decision must preserve:

- the route definition used for the decision;
- the ordered `triggering_fields`;
- `selected_for_review`;
- the decision's position in the full decision list.

The decision list must not be reconstructed from current rules and then represented as if it were the original historical list.

## 4. Event kinds and lineage rules

The initial event-kind vocabulary is proposed as:

- `original_assessment`: first assessment of a transition under a named snapshot;
- `reassessment`: a new assessment of an existing transition under a different or explicitly re-applied snapshot;
- `branch_fork`: an explicit branch-identity transition, if represented in this event stream.

This vocabulary is a proposal, not yet a code-level enumeration. Any additional kind requires a documented meaning and compatibility treatment.

For `reassessment`, `original_assessment_event_id` is required and must resolve to the preserved original event. The reassessment receives its own `event_id`; it does not replace the original. A fork event must retain the source branch identity and the new branch identity, and must not be inferred from a conversation change alone.

## 5. Mapping to current Lean structures

| Logical field | Current model support | Gap / note |
|---|---|---|
| Before and after state | `TransitionRecord` endpoints | In-memory only |
| Project and branch IDs | `ProjectState.projectId`, `branchId` | Present in endpoints; no durable event envelope |
| State version and tracked lists | `ProjectState` | Present in endpoints |
| Derived change set | `deriveChangeSet` | Result can be derived; derivation version is not stored |
| Rule snapshot ID and routes | `PropagationRuleSnapshot` | Present, but uniqueness and immutability are not enforced |
| Original route decisions/order | `PropagationAssessment.decisions` | Present in value; not externally persisted |
| Identity/branch review | `IdentityBranchReview` | Present in `TransitionReviewEntry` |
| Ordered in-memory history | `VersionedTransitionReviewHistory.entries` | No durable sequence or concurrent-writer semantics |
| Stable event ID | None | Missing |
| Schema name/version | None | Missing |
| Event kind and parent lineage | None | Missing |
| Idempotency key | None | Missing |
| Integrity digest/canonical encoding | None | Missing; encoding choice unresolved |
| Commit receipt/durable status | None | Missing |
| Real restart recovery | None | Not tested/implemented |

## 6. Canonicalization and integrity are blocked decisions

Before a digest is implementable, the project must define a canonical representation that specifies at least:

1. character encoding and normalization;
2. field order and field-name representation;
3. ordered-list preservation;
4. treatment of optional, missing, and unavailable values;
5. integer and string representation;
6. versioned digest algorithm and what bytes are covered.

Until these are selected, the record may carry integrity metadata only as a schema slot; no digest may be claimed as verified. A digest detects changes only within its stated threat model and is not, by itself, proof of authorship or trusted origin.

## 7. Acceptance gates

This draft can move to implementation only after review confirms:

1. all fields in the parent durable-history contract are mapped;
2. historical assessment and reassessment have distinct event identities and explicit lineage;
3. order-sensitive lists and missing-value semantics are defined;
4. schema evolution and unsupported-version behavior are explicit;
5. canonical encoding and integrity design are decided before claiming digest verification;
6. a backend-neutral adapter interface can represent committed, duplicate, conflict, unavailable, corrupt, and uncertain-commit outcomes.

These six gates are proposed checks, not completed test results.

## 8. Open questions

- Are parent links sufficient, or is an explicit sequence plus parent link required for every event?
- Should large states be embedded, content-addressed, or both?
- Which canonical encoding and digest algorithm should be adopted?
- How are event IDs and idempotency keys generated and scoped?
- Which concurrency policy governs simultaneous writers?
- What retention, backup, access-control, and deletion policy applies?

No implementation or backend decision is implied by this draft.
