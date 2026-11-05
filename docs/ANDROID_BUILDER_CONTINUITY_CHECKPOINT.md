# Android Builder Runtime — Phone-First Checkpoint

**Branch:** `independent-builder-runtime-foundation-001`
**Current source commit:** `a85b18155c3c8f12739104d5ddc8b8bc12e3f4f9`
**Android application ID:** `org.yggdrasil.builderhost`
**Display name:** Yggdrasil Builder Host
**Device target:** Samsung Galaxy S21 / Android 15 (user-reported). No new APK has yet been installed on the physical phone.

## Implemented

- Nine stable Builder role identifiers and immutable registry.
- Versioned, transport-neutral Builder message envelope.
- Separate SQLite continuity journal, independent of scheduler database.
- Unique event IDs, append-only events, SHA-256 linked hash chain and full-chain verification.
- Transactional idempotent message receipts.
- Versioned JSON snapshot export and validated restore.
- Restore checks format/version, event ordering, event IDs, hash links, duplicate IDs and receipt references before transactionally replacing local records.
- Android document-picker export to user-selected JSON file.
- Android document-picker import with 10 MB input limit, preview and explicit confirmation before replacement; invalid snapshots are rejected without replacing existing records.
- Robolectric recovery tests: snapshot round-trip, duplicate receipt protection, tamper rejection without mutation, unsupported version rejection.
- Deterministic local command router:
  - “remember that …”, “record note …”, “save note …”, “tell Muninn …”
  - “check memory”, “verify memory”, “memory status”
  - unsupported requests are identified rather than falsely reported as completed.
- Android Builder Console connected to this router.
- Pending snapshot export survives Android activity recreation while the document picker is open.
- Independent runtime now has a distinct Android application ID and display name, so it can be installed alongside the existing scheduler app without replacing it or sharing its private app data.

## Verified and pending

- Android CI passed at commit `53523547721ddf357b9c07862cede310fa06e28b`, including unit tests, Android compilation and APK upload:
  https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42002603400
- The source at commit `a85b18155c3c8f12739104d5ddc8b8bc12e3f4f9` includes the lifecycle preservation and separate app identity. Its Android CI run is pending:
  https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42003024625
- The command router and its tests passed in the Android CI run at commit `24fa009c7029e7247fcccb2812f0ceac866b6b6d`; the subsequent Android console integration passed at `5352354`.
- No physical phone installation or on-device test has occurred.

## Scope boundaries

The command router is deterministic and intentionally limited. It is not yet a general conversational model. The Mari-to-Muninn exchange is a local demonstration inside one app process, not nine independent agents. No arbitrary app control, external messaging, purchasing, destructive operations, local inference, offline speech or timed background Builder execution is claimed.

The journal hash chain is tamper-evident, not cryptographic authentication against an attacker able to rewrite the database. Snapshot JSON is not encrypted; encrypted backup and key management remain future work.

## Next gates

1. Complete CI for the separate application identity and final phone-ready source.
2. Obtain the latest debug APK artifact and verify its package identity.
3. Install alongside (not over) the existing scheduler app on the Galaxy S21.
4. Test command routing, journal verification, export, import, cancellation and tampered-file rejection.
5. Only after physical tests, benchmark local inference, thermal/battery/memory limits and offline voice.

## Integrity check

One active independent runtime branch; protected `main` and scheduler branch untouched; new app ID prevents package collision with the earlier scheduler app; scheduler and Builder databases separate; nine role identities; import requires confirmation and validation; automated CI is not confused with physical-device verification.