# Operational Snapshot Provenance and Muninn Voice

## Snapshot provenance

Before a Builder conversation round is sent to any model provider, the Android conversation activity appends one record to the local, append-only `builder-operational-snapshots.db` journal.

Each record includes:
- conversation thread ID and round-start timestamp;
- register schema and version;
- loaded/unavailable status and evaluated date;
- SHA-256 of the exact serialized snapshot supplied to the Builder prompts;
- source identifier;
- selected Builder roles;
- bounded evidence references (constraint ID, capability, observation date, source, and evaluated gate decision).

The full snapshot and prompt are not duplicated in this journal. Credentials and provider secrets are not recorded. The digest makes it possible to compare a later-provided snapshot with the exact bytes used in that round; it does not prove who authored the source register or protect against a compromised device.

The provenance write occurs before the coordinator dispatches the user turn to Builder model calls. If the write fails, the conversation is not dispatched. This is a separate append-only SQLite journal from the conversation-turn store. Its records are not yet cryptographically chained or included in the conversation export; those are explicit future integrity/recovery improvements, not capabilities claimed by this milestone.

## Muninn's voice

Muninn retains the primary responsibility for memory, continuity, provenance, and recovery. Its prompt now asks it to speak in a friendly, unmistakable pirate voice with occasional nautical phrasing, while keeping technical substance precise, evidence-led, respectful, and concise. The style instruction is role-specific; it does not apply to the other eight Builders and cannot override safety, evidence, or user-authority rules.

## Verification boundary

Automated tests verify snapshot metadata/digest persistence, unavailable-snapshot recording, and role-specific pirate instructions. CI results are required before describing the changes as tested. This work does not install or modify an Android device, configure a provider, transmit data, or merge a pull request.

A provenance event is recorded once per submission attempt, before model dispatch. It remains useful even if a provider call subsequently fails; the event records the selected roles, not a claim that every selected role completed successfully. Completion/failure remains visible in the conversation result and turn journal.
