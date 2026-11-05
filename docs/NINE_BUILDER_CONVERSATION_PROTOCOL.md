# Nine Builder Conversation Protocol

**Status:** Implemented as a versioned, typed validation contract with a local append-only SQLite journal; runtime orchestration and independent Builder workers remain pending.
**Protocol version:** 2 (reads legacy version 1 Builder-only turns)
**Authority:** The user remains director and final authority for consequential commitments.

## Purpose

Give the Nine Builders a shared way to contribute, question, challenge, answer, hand work to one another, acknowledge receipt, reconcile differences, and record decisions without flattening their distinct responsibilities.

This protocol is transport-neutral. It can be used by the current single-process prototype and later by separate workers or machines without changing the meaning of a conversation turn.

## Participants

The conversation has ten possible participants: the user (`USER`) and the Nine Builders. The user is a first-class speaker and recipient in the protocol, not an out-of-band annotation. User-authored directions, questions, corrections, and decisions can therefore be preserved in the same thread as Builder contributions and responses. The user remains the director and final authority for consequential commitments.

## The Nine Builder voices

| Builder | Primary responsibility |
|---|---|
| Huginn | Architecture, objectives, and scope |
| Muninn | Memory, continuity, provenance, and recovery |
| Kvasir | Research, evidence, and structured knowledge |
| Mari | Relationships, interfaces, integration, and distributed cooperation |
| Höðr | Implementation, execution, and testing |
| Týr | Integrity, permissions, and security |
| Loki | Alternatives, innovation, and failure recovery |
| Odin | Integration, reconciliation, and dependencies |
| Baldr | Independent verification against requirements |

Each role keeps its own primary responsibility. Any Builder may contribute, ask questions, challenge another Builder, or identify a risk outside its primary area. A primary responsibility is an accountability anchor, not a conversational restriction.

## Turn structure

Every turn has:
- protocol version;
- thread ID and unique turn ID;
- speaking Builder;
- optional recipient Builder;
- optional referenced earlier turn;
- turn type;
- explicit evidence status;
- timestamp;
- bounded text body.

Turn types:
- **CONTRIBUTION** — add an observation, proposal, analysis, or work result.
- **QUESTION** — ask a named Builder for a response.
- **CHALLENGE** — contest a specific earlier turn; the challenged Builder remains able to answer.
- **RESPONSE** — answer a named Builder and point to the turn being answered.
- **HANDOFF** — transfer a bounded task or question to another Builder, referencing its origin.
- **ACKNOWLEDGEMENT** — confirm receipt of a handoff or response; receipt is not agreement.
- **RECONCILIATION** — explain what is resolved, still disputed, or awaiting evidence, referencing the relevant turn.
- **DECISION** — record a proposed or user-authorized conclusion. A DECISION turn is a record, not an execution command.

## Evidence vocabulary

- **PROPOSED** — suggested, not yet exercised.
- **OBSERVED** — directly observed, with the observation context recorded.
- **TESTED** — a specified test was run; its scope and result must be stated.
- **VERIFIED** — supporting evidence has been checked against the relevant acceptance criteria.
- **USER_CONFIRMED** — the user reports the result; it is not described as independently observed.
- **DISPUTED** — a material disagreement or contradictory evidence remains.
- **UNKNOWN** — not established.

Evidence status applies to the claim in that turn, not to the speaker's identity or the entire thread. A later turn may correct or supersede a claim; prior turns remain in history.

## Conversation rules

1. **Distinct voices:** identify the Builder speaking. Do not silently merge separate contributions into a generic collective voice.
2. **Direct dialogue:** address another Builder by recipient and reference the turn being answered or challenged.
3. **Disagreement is retained:** a challenge does not delete the original claim. Record the response and any remaining dispute.
4. **Handoffs are explicit:** name the receiving Builder, identify the referenced task/context, and require an acknowledgement before treating receipt as confirmed.
5. **Reconciliation does not erase provenance:** Odin may synthesize the current position, but the source contributions and unresolved differences remain visible.
6. **Verification is independent:** Baldr checks conclusions against stated requirements and evidence; authorship alone is not verification.
7. **Memory carries context:** Muninn preserves thread identity, references, evidence status, decisions, and recovery context.
8. **Scope stays bounded:** Huginn keeps the question and acceptance criteria visible; Mari checks interfaces and relationships; Kvasir distinguishes sources from interpretation; Höðr reports what was actually implemented and tested; Týr checks permissions and integrity; Loki tests alternatives and failure paths.
9. **User authority is explicit:** no Builder gains authority from being named, receiving a handoff, or recording a DECISION turn.

## Validation rules implemented in code

The BuilderConversationProtocol validator checks:
- supported protocol version and required fields;
- non-empty bounded body;
- required recipient and reply references for directed turn types;
- no self-addressing;
- unique turn IDs and one thread identity;
- strictly increasing timestamps in supplied thread order;
- replies and handoffs reference earlier turns;
- responses, challenges, and acknowledgements name the Builder who authored the referenced turn; a handoff names its new recipient while retaining a reference to its source turn.

Protocol version 2 adds `USER` as a first-class participant. Version 1 Builder-only turns remain readable so existing local history can be recovered; new turns use version 2. This compatibility is limited to the unchanged turn fields and existing Builder participant names.

The validator is structural. It does not prove the truth of a claim, authenticate a remote sender, call a model, or execute work. The local journal persists validated turns in a private SQLite database, preserving insertion order and thread provenance across store closure and reopening. Appends are transactional; invalid turns are rejected before commit. The public store API exposes reads and appends only, not update or delete operations. The journal is currently device-local: it does not synchronize across devices, encrypt records independently of Android's app-private storage protections, authenticate remote senders, or provide a backup/export format. Database schema changes require an explicit migration; the current upgrade path deliberately fails rather than silently discarding history.

## First conversation acceptance exercise

Run a scripted conversation with all nine voices represented and these observable stages:
1. Huginn states a bounded question and acceptance criteria.
2. Kvasir contributes sourced findings and labels uncertainty.
3. Loki challenges one material assumption.
4. The original contributor responds.
5. Mari identifies interface or relationship effects.
6. Týr checks authority and safety boundaries.
7. Höðr proposes and reports a bounded implementation/test.
8. Muninn records the handoff, provenance, and continuity checkpoint.
9. Odin reconciles the thread; Baldr independently checks the conclusion.
10. The user accepts, rejects, or revises any consequential decision.

The exercise is a protocol test, not evidence of nine independently executing agents. The first runtime test should use one process and explicit simulated voices. Later tests can replace roles with independent workers while retaining the same message contract.

## Non-goals and safety boundaries

- No model-provider selection or API credentials are added here.
- No external contact, purchases, deployment, destructive changes, or permission escalation is authorized.
- No Builder may claim to be a separately running agent until separate worker processes and their identity/isolation are implemented and verified.
- No changes are made to Builder Host production signing, Scheduler behavior, or production data.

## Local conversation journal

`BuilderConversationStore` is a SQLite-backed, append-only local journal. It stores the complete typed turn fields, including thread and turn IDs, speaker (user or Builder), recipient, reply reference, turn type, evidence status, timestamp, and body. A new append is validated against the existing thread inside a database transaction before insertion. Reads return immutable snapshots in insertion order. The journal has no public update/delete method. Its current schema is version 1; schema upgrades must be implemented as explicit migrations.

The journal is not yet connected to a model coordinator, background workers, remote transport, cross-device synchronization, or an automatic backup mechanism. Those remain separate future capabilities and must not be inferred from local persistence.

## Integrity checkpoint

- **Count:** eight turn types; seven evidence states; nine Builder roles plus the user as director (ten participants).
- **Role mapping:** all nine primary responsibilities are represented once.
- **Omissions/duplicates:** the user is a first-class participant; receipt is distinct from agreement; reconciliation is distinct from decision; a decision record is not an execution permission.
- **Evidence:** validator and journal source plus unit tests are committed in this branch; CI execution of the journal tests is pending. Actual model-coordinated dialogue and independent workers remain pending.
- **Correction:** malformed references, duplicate IDs, cross-thread turns, invalid recipient links, and out-of-order timestamps are rejected.
- **Persistence:** source and tests are committed to the development branch only; no deployment or production change.
