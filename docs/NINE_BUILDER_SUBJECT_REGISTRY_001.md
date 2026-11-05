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

The index uses a separate database file, so this milestone does not alter or migrate the existing conversation journal database. The conversation screen now records a uniquely matched subject, its matched subheadings, and the selected Builders as one atomic metadata batch before submitting the conversation round. If this non-authoritative sidecar write fails, the conversation is allowed to proceed and the UI reports that continuity metadata was unavailable. This does not create a cross-database transaction with the canonical journal. A versioned recovery envelope now packages the canonical conversation snapshot and metadata snapshot separately, with nested and outer SHA-256 checksums. Validation remains read-only. `BuilderRecoveryStager` can restore a validated package into two newly named, isolated SQLite databases, read both back, and compare their contents with the source snapshots. It refuses to overwrite an existing staging pair and removes files created by a failed staging attempt. The staged pair is not activated, and neither live database is replaced or modified. Cross-device synchronization remains future work.

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
- The conversation screen records matched subject, subheadings, and selected participants atomically in the sidecar.
- A sidecar write failure does not block or rewrite the canonical conversation journal.
- Metadata export is checksummed and excludes canonical turn bodies and credentials.
- Existing conversation journal schema and history remain unchanged.
- No claim is made that nine independent model workers are operational.

## Remaining implementation

1. Add validated snapshot import/restore and include the index in the recovery export package.
2. Test subject continuity, export/restore and journal integrity on the Galaxy S21+ using non-sensitive test content, after explicit approval for any installation or device change.
3. Add cross-device synchronization only with explicit conflict and provenance rules.


## Recovery package envelope — version 1

The next recovery milestone adds `BuilderRecoveryPackage`, which packages two independently checksummed snapshots into one versioned envelope:

- `conversation_snapshot`: canonical turns exported by the existing conversation journal.
- `subject_thread_snapshot`: append-only subject/subheading/participant events exported by the sidecar index.

The package has its own SHA-256 checksum in addition to each nested snapshot's checksum. Validation checks format versions, all three checksums, declared counts, unique thread/event identities, conversation turn shape and ordering, and subject event shape/Builder participant identities. It reports subject metadata whose thread ID is absent from the canonical conversation snapshot as an orphan rather than silently dropping it; this can happen when routing metadata is recorded but a subsequent conversation submission fails.

**Important boundary:** this milestone builds and validates the package only. Validation is read-only and does not restore or merge data into either live database. SHA-256 detects accidental changes; it does not encrypt the backup, authenticate its source, or protect against a party able to replace both data and checksums. A safe restore writer still needs staged target databases, explicit activation, duplicate/conflict handling, and failure recovery before it can be enabled.


## Isolated recovery staging — version 1

`BuilderRecoveryStager` is a staging-only restore path. It validates the complete recovery envelope before opening any database, requires a restricted staging identifier, and refuses to reuse either database filename if it already exists. It writes the canonical turns and subject events into separate new SQLite files, then reads the staged records back and compares them with the source snapshots.

If staging fails, only the two files created for that attempt are deleted. Existing live stores are not opened as write targets, replaced, renamed, or deleted. The result reports both staged database names and record counts. It does **not** activate the pair or make either staged database authoritative.

A future activation protocol must coordinate the two files as a pair, handle interruption between activation steps, retain the prior active pair for rollback, and define duplicate/conflict behavior. Individual SQLite transactions do not provide an atomic transaction across these two separate database files. Until that protocol and its fault-injection tests exist, staged recovery is not live recovery.

Acceptance checks added:
- Invalid envelopes are rejected before staging files are created.
- Staging identifiers cannot contain path traversal characters.
- Existing staging files are never overwritten.
- Both staged databases are read back and compared with their source snapshots.
- Live conversation and subject databases remain unchanged.
- No activation, live-store replacement, device installation, or cross-device synchronization is performed by this milestone.
