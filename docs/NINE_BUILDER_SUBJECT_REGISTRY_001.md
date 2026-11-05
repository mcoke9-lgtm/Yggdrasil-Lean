# Nine Builders Subject Registry — v1

**Status:** Working design adopted by the user; repository implementation under review.
**Registry version:** 1.0.0
**First subject:** Cryptography
**Conversation protocol:** `docs/NINE_BUILDER_CONVERSATION_PROTOCOL.md`, protocol version 2

## Purpose

The subject registry provides a shared entry point into the Nine Builders' knowledge and responsibilities. A person can name a subject without knowing which Builder handles a technical subproblem. The registry identifies primary stewards and supports assembling them into a direct conversation with the user.

The registry sits above the existing conversation protocol. It does not replace participant identities, typed turns, evidence statuses, handoffs, challenge/reply references, or user authority.

## Subject record structure

Each subject contains a stable `subject_id`, heading, summary, included and excluded scope, lifecycle status, exactly nine primary stewardship assignments, subheadings, deterministic recognition terms, governance rules, a conversation protocol reference, and optional thread references.

The machine-readable registry is `subject-registry/registry.json`; its JSON Schema is `schemas/subject-registry-v1.schema.json`. Cryptography is the first subject and currently defines thirteen subheadings.

## Builder stewardship

Primary stewardship is an accountability anchor, not exclusive ownership. Any Builder may join, challenge, or ask a question outside their primary area. The user may invite additional Builders, choose all nine, or disable automatic routing and select participants manually.

## Conversation assembly

1. Recognize a subject heading or one of its recognition terms.
2. Match the question to one or more subheadings.
3. Select the primary stewards for those subheadings.
4. Include the user as a first-class participant and director.
5. Allow additional Builders to join without removing primary stewards.
6. Stop before sending if a match is ambiguous.
7. Preserve turns, references, evidence statuses, challenges, handoffs, failures, and disagreements in the canonical conversation journal.

A subject match is routing, not permission. It does not authorize access to a device, account, repository, secret, key, or external service.

## Subject-to-thread continuity

Conversation turns remain canonical in `BuilderConversationStore`. Subject associations must not be copied into or used to rewrite those turns.

The new `BuilderSubjectThreadIndex` stores a separate append-only metadata event stream in its own SQLite database. It records:
- subject attached to a conversation thread;
- subheading attached to that thread;
- participant selected for that subject conversation.

Each event carries a unique event ID, thread ID, subject ID, event type, optional subheading or participant, and timestamp. A thread can acquire multiple subjects and multiple subheadings over time. Events are append-only; duplicate event IDs are rejected.

The index can export a metadata-only JSON snapshot with a SHA-256 content checksum. This checksum detects accidental content changes; it is not encryption, authentication, or proof of origin. The snapshot intentionally does not duplicate conversation turns or include provider credentials.

The index uses a separate database file, so this milestone does not alter or migrate the existing conversation journal database. The current index implementation is a storage primitive: automatic wiring from the conversation screen/coordinator, import/restore, cross-device synchronization, and end-to-end export packaging remain future work.

## Evidence and decision handling

Use the conversation protocol evidence vocabulary: `PROPOSED`, `OBSERVED`, `TESTED`, `VERIFIED`, `USER_CONFIRMED`, `DISPUTED`, and `UNKNOWN`. Evidence status applies to an individual claim, not a whole subject or speaker.

Subject lifecycle status is distinct from evidence status. A subject may be adopted as an organizational heading while a technical claim remains unknown or disputed. A decision turn records a decision; it is not an execution command. Consequential actions still require the applicable approvals.

## Runtime boundary

The Android subject resolver maps user text deterministically to subheading stewards. A broad subject heading selects all nine. The user can add other Builders without removing stewards or turn automatic routing off for manual selection. Ambiguous matches stop before sending.

The current coordinator remains single-process. The nine roles are coordinated perspectives; nine independently running model workers have not been established. The subject-thread index does not change that boundary.

## Acceptance checks

- Registry parses and validates against its JSON Schema.
- Exactly one Cryptography record exists with a stable ID.
- All nine Builder IDs appear exactly once in subject-level stewardship.
- Every subheading has at least one valid Builder steward.
- Resolver matching is deterministic; ambiguous matches stop before sending.
- User-invited Builders are added without duplicates or removing stewards.
- The user remains a first-class participant and final authority.
- Subject-thread events are append-only and uniquely identified.
- A conversation thread can accumulate multiple subjects/subheadings.
- Metadata export is checksummed and excludes canonical turn bodies and credentials.
- Existing conversation journal schema and history remain unchanged.
- No claim is made that nine independent model workers are operational.

## Remaining implementation

1. Integrate index event writes with the conversation screen/coordinator.
2. Add validated snapshot import/restore and include the index in the recovery export package.
3. Test subject continuity, export/restore and journal integrity on the Galaxy S21+ using non-sensitive test content.
4. Add cross-device synchronization only with explicit conflict and provenance rules.
