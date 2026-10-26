# Branch Continuity Specification 001

**Status:** Proposed design; not yet implemented or tested as an executable model.

## Purpose

A change of conversation must not, by itself, create a new project branch. A conversation is a location where work happens; a project branch is a continuing line of work; a state transition records what actually changed.

## Core invariant

A conversation transition may change the working context without changing project or branch identity. Every meaningful state change should preserve provenance, record changed relationships, and make missing or unrecoverable information explicit.

## Minimal state record

```yaml
project_id: yggdrasil
branch_id: adaptive-filesystem
state_version: 1
inherits_from: null
depends_on: []
agreements: []
evidence: []
open_questions: []
transition:
  type: conversation_change
  changed_fields: []
  preserved_fields:
    - project_id
    - branch_id
  unresolved: []
```

This is an illustrative record, not a finalized schema. Identifiers, versioning rules, provenance formats, and merge semantics remain to be specified.

## Six acceptance checks

1. **Identity survives:** project and branch identifiers remain unchanged when only the conversation changes.
2. **History survives:** prior agreements and evidence retain their source and provenance.
3. **Dependencies survive:** relationships to other branches remain discoverable after the transition.
4. **Changes propagate selectively:** only state and relationships affected by a change are updated.
5. **Prior state is recoverable:** a prior version can be reconstructed from retained records, or the limits on reconstruction are explicitly reported.
6. **Uncertainty is visible:** missing context and unresolved conflicts are flagged rather than filled in by inference.

## Candidate test cases

- Open a new conversation with no project-state changes: identifiers and recorded state remain stable.
- Change one agreement: the agreement's provenance and version change; unrelated branch identities remain stable.
- Remove or omit a dependency record: the validation should flag the missing relationship rather than invent it.
- Attempt to reconstruct an earlier version after a change: reconstruction succeeds from the recorded history, or reports exactly what is missing.
- Present conflicting snapshots: the system flags the conflict and does not silently choose one.
- Start a conversation without sufficient prior context: the system marks unknown fields as unresolved instead of treating them as empty facts.

## Relationship to TERA / FIRA / WATA / AIRA

- **TERA:** continuity conditions and identity constraints.
- **FIRA:** recorded relationships, dependencies, and propagation.
- **WATA:** state/configuration changes.
- **AIRA:** newly emerging possibilities and unresolved outcomes.

These are conceptual placements for review, not compiler-verified definitions.

## Verification boundary

This document does not establish that the model is implemented. The six checks are proposed acceptance criteria, not executed tests. A future implementation should define a concrete state type and transition function, then test identity preservation, provenance, selective propagation, reconstruction, and uncertainty handling. Lean compilation can verify formalized propositions, but cannot alone establish that the conceptual model matches the intended project semantics.
