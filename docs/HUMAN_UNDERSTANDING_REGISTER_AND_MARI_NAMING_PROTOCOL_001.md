# Human Understanding Register and Mari Naming Protocol 001

**Status:** Initial implementation proposal; code and tests are included in this branch, but are not yet merged or runtime-verified.  
**Component:** YIM-001 (Yggdrasil Intake and Memory)  
**Scope:** Stable technical identities, human-readable interpretations, Character-generated naming proposals, and separately authorized adoption.

## 1. Purpose

Technical identifiers remain stable machine-facing references. Each may have a human-readable accompanying name and plain-language interpretation so people can understand, discuss, and retrieve the work without replacing its technical identity.

The register is intended to be portable across Living Tree hosts. It is not dependent on ChatGPT conversation memory. ChatGPT may help draft or review records, but the durable source of truth must be the versioned register stored by the Living Tree.

## 2. Mari's responsibility

Mari is the designated naming and interpretation proposer. For each request, Mari should:

1. Read the technical identifier, purpose, keywords, and preserved terminology.
2. Offer multiple human-readable alternatives.
3. Explain the meaning, strengths, and limitations of each alternative.
4. Identify competing interpretations, including technical, cultural, and audience-specific readings.
5. State a preferred option when useful, while preserving the alternatives and reasons.
6. Identify ambiguity, misleading implications, cultural sensitivities, and premature claims (for example, describing an unformed proposal as an established partnership).
7. Preserve disagreement rather than silently resolving it.

A record attributed to Mari is a role-attributed system output. It does not establish that an autonomous person or independent process named Mari executed it. The runtime must record the actual execution source separately if that distinction matters.

## 3. Authority boundary

- Mari proposes; Mari does not adopt.
- Odin coordinates and reconciles; Odin does not erase Mari's dissent.
- Huginn supports evidence and provenance.
- Muninn preserves prior proposals, decisions, and superseded wording.
- Týr checks the authorization path.
- A human-authorized decision is required before a name becomes adopted.
- Access to propose, review, or read a name is not authority to adopt it.

The implementation's `human_authorized` field is an explicit caller assertion, not authentication. A production host must enforce the authorization boundary using its own trusted identity and approval mechanism. The library must never represent a caller-supplied string as proof of identity.

## 4. Record model

Each register entry preserves:

- `entry_id`: stable register-entry identity.
- `technical_id`: immutable machine-facing project/component identifier.
- `purpose`: plain-language purpose.
- `keywords`: retrieval terms.
- `preserved_terms`: terminology that must not be silently replaced.
- `proposals`: append-only naming proposals and their alternatives.
- `decisions`: append-only adoption, decline, deferral, or supersession events.
- `current_human_name`: derived view from recorded decisions; never an independently overwritten field.

Each event has a stable caller-supplied `event_id`, contiguous per-register sequence, event type, actor identifier, and payload. Events are immutable. Corrections and new interpretations append new events.

## 5. Lifecycle

1. Create an entry for a technical identifier.
2. Mari submits a proposal with at least two alternatives, tradeoffs, and competing interpretations.
3. The proposal is persisted before it is presented as a saved proposal.
4. A human reviews the alternatives.
5. Týr's authorization path records an explicit decision. Only an authorized adoption changes the derived current human-readable name.
6. Later changes append new decisions; prior proposals and decisions remain in history.

No silent replacement, auto-adoption, or inference from conversation agreement is permitted.

## 6. Portability and persistence

The YIM-001 module uses the existing atomic JSON storage helpers. A host can save and reload the register without ChatGPT. The JSON snapshot is the portable interchange format; a host may later move it to a database if it preserves the event contract.

A successful in-memory operation or JSON serialization is not proof of persistence. The host should report durable success only after writing and reading back the saved snapshot. Backups, access control, encryption, multi-writer compare-and-append, and cross-device synchronization remain host-level requirements and are not implemented by this module.

## 7. Initial MARS-BRIDGE-001 Mari proposal

Technical ID: `MARS-BRIDGE-001`

Purpose: Coordinate voluntary cultural, scientific, and educational cooperation for sustainable human futures, including Mars preparation and useful Earth-based work.

Mari's initial preferred option:

**Building Bridges to a Shared Future**

Meaning: Connecting communities and disciplines to build a peaceful human future on Earth and beyond.

Alternatives preserved:

- **A Shared Path Beyond Earth** — concise and clearly space-oriented, but less explicit about the Earth-based communities and relationships.
- **Earth-to-Mars Partnership** — direct and easy to understand, but may imply formal partners already exist.

Competing interpretations to keep visible:

- The initiative is primarily about Mars exploration and settlement.
- The initiative is primarily about cultural change and international cooperation on Earth.
- The initiative must communicate both purposes without implying that institutional partnerships already exist.

**Status:** Mari proposal only. No human-readable name is adopted by this record. The technical identifier remains `MARS-BRIDGE-001`.

## 8. Acceptance tests and status discipline

The accompanying tests cover:
- stable technical identity and duplicate rejection;
- Mari role attribution and multiple alternatives;
- strengths, limitations, and competing interpretations;
- explicit human authorization before adoption;
- append-only sequence;
- save/load restart round-trip;
- unknown schema and malformed sequence rejection.

Tests must be executed in the target runtime before they are labelled passed. Unit tests do not establish multi-device durability, authorization security, concurrent-writer safety, or Android integration.

## 9. Current limitations

- This branch does not merge itself.
- The module is not yet wired into an Android service or an always-running Character scheduler.
- Mari's role is specified and a sample proposal is recorded; autonomous Character execution is not implemented.
- The library does not authenticate Týr or the human approver.
- Multi-writer concurrency and durable cross-device synchronization are not implemented.
- ChatGPT cannot install this behavior as a permanent global rule across all conversations. The Living Tree can use the versioned contract as its own system behavior once integrated by its host.

## 10. Six-part integrity check

1. **Identity:** Technical IDs remain stable and separate from display names.
2. **Alternatives:** Multiple interpretations and tradeoffs are preserved.
3. **Authority:** Proposal and adoption are distinct; adoption requires an explicit authorization assertion.
4. **History:** Events are append-only and sequence-checked.
5. **Recovery:** The register supports JSON save/load through YIM-001 storage helpers.
6. **Honesty:** Role attribution is not misrepresented as autonomous execution; unimplemented host integration is disclosed.
