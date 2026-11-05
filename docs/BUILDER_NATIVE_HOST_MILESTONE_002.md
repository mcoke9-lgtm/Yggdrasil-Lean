# Builder-Native Host — Milestone 002: Canonical Journal Ownership

**Status:** Implemented in development branch; Android and journal CI verification pending.
**Branch:** independent-builder-runtime-foundation-001
**Current deployment target:** Galaxy S21 Android host.
**Future alternative host:** NUC running a selected, supported operating system.
**Authority:** User remains director and final authority.

## Architecture decision

The Builder-native process—not the Android conversation screen—now owns the canonical conversation journal.

- The Android UI is a client.
- The Builder-native process is the journal owner.
- The journal contract is expressed as a transport-neutral Java interface.
- Android uses a versioned Messenger IPC adapter.
- The existing SQLite conversation database name and schema are retained, so this change does not intentionally create a second conversation-history database or erase the existing one.
- The service performs journal operations on its own worker thread.
- Conversation turns remain validated and append-only.
- Read, thread-list, and JSON export operations are served by the native host.

## Runtime interface in this milestone

Protocol version 1 currently supports:
- health and capability reporting;
- append a validated conversation turn;
- read a thread in append order;
- list saved thread identifiers;
- export a complete JSON snapshot with SHA-256 integrity checksum.

The existing BuilderConversationCoordinator now depends on the transport-neutral BuilderConversationJournal contract, not the Android SQLite implementation. This makes the coordinator reusable behind a future Linux host adapter.

## Three-host relationship

1. **ChatGPT host:** optional model reasoning, research, and language collaboration.
2. **Android host:** Galaxy S21 interface, notifications, device capabilities, and mobile operation.
3. **Builder-native host:** identity, canonical memory, journal integrity, coordination boundary, and recovery ownership.

The Android deployment is the first implementation of the Builder-native host, not its permanent definition.

## NUC alternative-host plan

The future device is an Intel NUC5i5RYH (published CPU: 2 cores / 4 threads; published maximum RAM: 16 GB). It currently has no operating system installed. Installed RAM, storage, and network hardware remain unknown. Debian 13 minimal server is the current candidate, not an adopted installation decision. The user previously chose to defer NUC/USB work and develop on the Galaxy S21 first.

Before installing an OS:
- identify exact NUC model, CPU generation, RAM, storage, network interfaces, and firmware/boot options;
- inspect supported OS and driver compatibility;
- select a supported Linux distribution and update policy;
- design encrypted storage, backup, recovery media, and remote/local administration;
- define a secure host-to-host transfer and pairing protocol;
- test export/import and identity continuity before moving canonical ownership.

Do not wipe, repartition, or install an OS on the NUC until its exact hardware and recovery requirements are known and the user explicitly approves that installation.

## What is not yet implemented

- NUC OS installation or NUC deployment.
- Portable Linux service executable.
- Conversation import and verified restore.
- Host-to-host replication, synchronization, or conflict resolution.
- Multi-host authentication/pairing.
- Provider-independent model adapter in the native host.
- Autonomous task execution or independent AI processes.

The JSON export is available; a validated import/restore path is still required before hardware migration can be called safe. Runtime identity is persistent on the Galaxy S21, but not yet portable to another host.

## Verification gates

- **Implemented:** journal contract, native-host ownership, IPC client, append/read/list/export protocol.
- **CI pending:** Android build, tests, and workflow completion.
- **Device pending:** verify service process, journal write/read after process death, and existing conversation history on the Galaxy S21.
- **Recovery pending:** export, import, checksum validation, and restore drill.
- **NUC pending:** hardware inventory, OS selection, and installation approval.

## Integrity checkpoint

- **Host count:** three logical hosts; two current external platforms plus one Builder-native process hosted by Android.
- **Ownership:** Builder-native service owns canonical journal; Android screen uses IPC client.
- **Persistence:** existing conversation database path/schema retained; runtime identity in separate database.
- **No duplicate journal:** no second canonical conversation store introduced by this milestone.
- **Evidence:** code committed; CI/device/recovery verification not yet complete.
- **Boundary:** NUC remains untouched until inventory, plan, backup, and explicit approval.
