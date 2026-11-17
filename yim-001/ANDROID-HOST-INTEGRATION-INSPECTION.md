# Android Host Integration Inspection

**Inspection date:** 2026-11-17  
**Status:** inspected; implementation route proposed; no Android source changed by this inspection.

## Sources inspected

Android host source was inspected on branch `builder-android-ci-validation-004`:

- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/java/org/yggdrasil/scheduler/MainActivity.java`
- `app/src/main/java/org/yggdrasil/scheduler/BuilderConversationActivity.java`
- `app/src/main/java/org/yggdrasil/scheduler/SchedulerDb.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderLocalExchange.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderEventJournal.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderConversationStore.java`

This is a source inspection through GitHub, not a build or device test of this branch.

## Findings

1. The Android application ID is `org.yggdrasil.builderhost`; its Gradle namespace is `org.yggdrasil.scheduler`.
2. The host is an Android Java application with private app-local SQLite databases.
3. `MainActivity` already uses Android's Storage Access Framework:
   - `ACTION_CREATE_DOCUMENT` for JSON export.
   - `ACTION_OPEN_DOCUMENT` for JSON import.
4. The host has a local append-only Builder event journal with hash-linked events and an idempotent processed-message receipt table.
5. It also has a separate append-only conversation history store.
6. The Builder event journal is **not** the YIM-001 audited memory store. Its event payload format and persistence schema differ. Writing an intake packet into this journal would not constitute a Roots import.
7. `BuilderLocalExchange` is an in-process handoff demonstration, not an independent agent runtime or a YIM-001 integration.
8. The manifest disables cleartext traffic generally but permits localhost through its network security configuration. This does not establish that a local service exists or is required.
9. The existing host does not currently demonstrate that it can execute the YIM-001 Python package. The inspected Gradle configuration does not show a Python runtime integration.
10. The YIM-001 project explicitly describes its CLI as a local single-writer reference implementation and its phone intake page as a queue exporter. It is not presently a distributed sync service.

## Integration conclusion

**Do not write the phone queue into the Builder event journal and label it Roots.** That would conflate two distinct stores and give a false persistence guarantee.

The narrowest safe next implementation is an Android-native **pending intake staging** feature using the existing document picker and a dedicated SQLite table. This would make queue receipt and validation available on the phone without claiming that the canonical YIM-001 audited Tree store has been updated.

A later Roots adapter must be a separately specified and tested component. Candidate approaches:

- Port the required audited-memory, provenance, and logical-placement behavior into a Java/Kotlin implementation with cross-language fixture tests; or
- Package a supported Python runtime into the Android app and invoke the existing YIM-001 importer against an app-private audited snapshot.

Do not choose between these without a prototype that demonstrates format parity, atomic persistence, recovery, and repeat-import idempotency. A localhost HTTP bridge is not recommended as the first option: it adds a network listener and lifecycle/security complexity without solving the missing runtime integration.

## Staged phone intake requirements

- Import only a user-selected JSON file through `ACTION_OPEN_DOCUMENT`.
- Validate the exact YIM-001 queue format and supported version.
- Preserve packet IDs, timestamps, source kind/reference, language, exact content, metadata, and source-preservation marker.
- Store the original packet JSON bytes/text unchanged alongside parsed fields.
- Enforce unique queue and packet identity; duplicate imports must not duplicate staged packets.
- Stage packets as `awaiting_roots_import`; do not claim canonical Tree placement.
- Keep staged packets separate from the Builder conversation and event-journal databases.
- Provide inspect/export/remove-pending actions with explicit user confirmation for removal.
- No network transfer, cloud endpoint, or third-party database.
- Later canonical import must validate actual Tree node IDs and commit memory, provenance, and Tree placement through a tested transaction boundary.

## User's current test packet

Queue: `phone-intake`, format version 2.  
Packet: `60d49085-b029-400a-adc2-f746b2bc2eb4`.  
Content: `{"text":"Living Tree phone intake test","stage":"first capture"}`.  
Current state: supplied in chat and validated; not present in the Android app's private storage and not imported into the audited Roots store.

Proposed primary location: `roots.archive`.  
Contextual location: unresolved. Existing YIM-001 tests refer to `branches.projects`, but the actual Tree index has not been inspected to establish that this node exists.

## Verification still required

- Android unit tests for valid, invalid, oversized, duplicate, and repeated queue imports.
- Database transaction and restart persistence tests.
- Byte-for-byte source packet preservation.
- No cross-write into Builder journal or conversation database.
- Actual Android device test of document picker and local persistence.
- A separate verified Roots adapter and end-to-end test before claiming canonical import.
