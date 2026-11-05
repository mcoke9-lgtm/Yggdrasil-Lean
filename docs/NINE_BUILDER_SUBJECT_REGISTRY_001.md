# Nine Builders Subject Registry — v1

**Status:** Proposed repository implementation of the subject architecture agreed in conversation. The subject design is adopted as a working design; this repository representation remains proposed until validated and reviewed.
**Registry version:** 1.0.0
**First subject:** Cryptography
**Conversation protocol:** `docs/NINE_BUILDER_CONVERSATION_PROTOCOL.md`, protocol version 2

## Purpose

The subject registry provides a shared entry point into the Nine Builders' knowledge and responsibilities. A person should be able to name a subject without knowing which Builder owns a technical subproblem. The registry identifies relevant stewards and gives them a way to assemble into a direct conversation with the user.

The registry sits above the existing conversation protocol. It does not replace the protocol's participant identities, typed turns, evidence statuses, handoffs, challenge/reply references, or user authority.

## Core record structure

Each subject record contains:

| Field | Purpose |
|---|---|
| `subject_id` | Stable machine identifier; does not depend on display wording |
| `heading` | Human-readable subject heading |
| `summary` | Short description of the subject |
| `scope.includes / excludes` | Boundaries that prevent silent scope expansion |
| `lifecycle_status` | Proposed, drafted, built, tested, verified, or adopted |
| `stewardship` | Exactly nine primary responsibility assignments |
| `subheadings` | Narrower topics with one or more primary stewards |
| `conversation_entry` | Recognition terms and participant-assembly policy |
| `governance` | Nonexclusive stewardship, user authority, retained disagreement, and no self-certification |
| `conversation_protocol_ref` | Link to the shared turn protocol |
| `thread_refs` | Conversation threads associated with this subject |

The machine-readable record is `subject-registry/registry.json`; its JSON Schema is `schemas/subject-registry-v1.schema.json`.

## Builder stewardship

A primary steward is accountable for bringing the relevant perspective, evidence, questions, and follow-through. Stewardship is not exclusive ownership. Any Builder may join, challenge, or ask a question outside their primary area. The user may select participants or invite all nine.

The Cryptography record assigns all nine Builders and maps thirteen subheadings. This preserves both a recognizable point of responsibility and cross-role participation.

## Conversation assembly

1. Recognize a subject heading or one of its recognition terms.
2. Match the question to one or more subheadings.
3. Invite the primary stewards for those subheadings.
4. Include the user as a first-class participant and director.
5. Permit any Builder or the user to invite additional Builders; the user may request all nine.
6. Ask Baldr to independently review claims at the verification gate. Baldr's presence is not itself proof of verification.
7. Preserve all turns, references, evidence statuses, challenges, handoffs, failures, and unresolved disagreements under the existing conversation protocol.

A subject match is a routing suggestion, not a permission grant. It does not authorize access to a device, account, repository, secret, key, or external service.

## Subject conversation record

A conversation thread associated with a subject should be linked, not copied into the registry. The thread itself remains in the append-only conversation journal. A subject-thread index entry should contain:

- `subject_id` and the matched `subheading_ids`;
- `thread_id` and ordered `turn_ids`;
- `opened_by` and the selected participant list;
- creation and last-update timestamps;
- evidence references and decision references;
- unresolved questions and the current lifecycle status.

This separation keeps the registry stable while conversation history grows. It also avoids duplicating or rewriting the canonical turn history.

## Evidence and decision handling

Use the existing protocol evidence vocabulary: `PROPOSED`, `OBSERVED`, `TESTED`, `VERIFIED`, `USER_CONFIRMED`, `DISPUTED`, and `UNKNOWN`. Evidence status applies to an individual claim, not to a whole subject or speaker.

Subject lifecycle status must not be confused with evidence status. A subject can be adopted as an organizational heading while a particular technical claim remains unknown or disputed. A proposed registry file is not a tested runtime feature.

Decisions remain records, not execution commands. Consequential actions continue to require the approvals specified by the relevant project and safety rules.

## Current implementation boundary

The existing Android runtime branch contains a typed conversation protocol, a user participant, a role registry, a bounded single-process coordinator, and an append-only local conversation journal. The current coordinator receives a selected Builder list; it does not yet resolve a subject heading to subheadings and steward assignments. This registry adds the data contract for that next routing step.

Independent Builder workers, cross-device synchronization, and live subject-based routing are not established by this data definition. They require separate implementation and tests.

## Acceptance checks for this milestone

- The registry parses as JSON and validates against the supplied schema.
- There is exactly one Cryptography subject record with a stable ID.
- All nine Builder IDs appear exactly once in subject-level stewardship.
- Every subheading has at least one valid Builder steward.
- Recognition terms include the current cryptography and Android-signing vocabulary.
- The user is a first-class participant and retains final authority.
- Any Builder may join; primary stewardship is explicitly nonexclusive.
- The conversation protocol reference resolves to protocol version 2.
- No thread history, credentials, private keys, or secrets are embedded in the registry.
- No claim is made that subject-based runtime routing or independent workers are already operational.

## Next implementation step

Add a subject resolver that accepts a subject ID or recognized heading, returns matched subheadings and their primary stewards, allows user-selected additions or all-nine participation, and passes the selected roles into the existing conversation coordinator. Test deterministic matching, ambiguity, unknown subjects, missing stewards, Builder failure, and graceful continuation when one or more providers are unavailable.
