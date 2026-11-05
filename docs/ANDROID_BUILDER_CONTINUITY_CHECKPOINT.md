# Android Builder Runtime — Phone-First Checkpoint

**Branch:** `independent-builder-runtime-foundation-001`
**Current source commit:** `a85b18155c3c8f12739104d5ddc8b8bc12e3f4f9` (checkpoint document updated after phone test)
**Android application ID:** `org.yggdrasil.builderhost`
**Display name:** Yggdrasil Builder Host
**Device:** Samsung Galaxy S21 / Android 15 (user-reported). User confirmed successful installation of Yggdrasil Builder Host.

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

- Final Android CI passed for source commit `a85b18155c3c8f12739104d5ddc8b8bc12e3f4f9`: unit tests, Android compilation and debug APK upload all succeeded. Run `42003024625`:
  https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42003024625
- Uploaded artifact `13403960642` (`yggdrasil-builder-runtime-debug-apk`, 2,374,793 bytes; not expired at verification).
- User confirmed successful installation on the physical Samsung Galaxy S21.
- User confirmed the first on-device note command was sent and the journal verification reported success. This is user-reported physical-device verification of basic note capture and journal integrity check.
- User confirmed the exported snapshot file was visible in the phone's Downloads folder.
- User completed the app's import/validation action. The user observed a single action button and reasonably noted that validation and import appear combined in the current interface; this UX ambiguity is recorded rather than assuming a distinct two-stage confirmation.
- After the import action, the app's Journal Integrity control reported that the chain was verified.
- User then completely closed and reopened the app; Journal Integrity again reported that the chain was verified. This is user-reported physical-device verification of post-import integrity and application-restart persistence.
- User then restarted the Samsung Galaxy S21, reopened Yggdrasil Builder Host and ran Journal Integrity; the chain was again reported verified. This is user-reported physical-device verification of journal persistence across a full device reboot.
- Not yet physically verified: cancellation behavior, tampered-file rejection without loss of existing records, offline operation, or independent agent execution.

## Scope boundaries

The command router is deterministic and intentionally limited. It is not yet a general conversational model. The Mari-to-Muninn exchange is a local demonstration inside one app process, not nine independent agents. No arbitrary app control, external messaging, purchasing, destructive operations, local inference, offline speech or timed background Builder execution is claimed.

The journal hash chain is tamper-evident, not cryptographic authentication against an attacker able to rewrite the database. Snapshot JSON is not encrypted; encrypted backup and key management remain future work.

## Next gates

1. Test invalid/tampered snapshot rejection without losing existing notes; do not clear app data.
2. Clarify whether import preview and final replacement are separate UI actions; user observed one action button.
3. Test offline behavior and capability boundaries before selecting local inference or voice components.

## Integrity check

One active independent runtime branch; protected `main` and scheduler branch untouched; distinct app ID is set in source and the user successfully installed the app; scheduler and Builder databases remain separate by design; nine role identities; import requires confirmation and validation; automated CI success is distinguished from user-reported physical-device checks. Export, import action, post-import chain verification and app-restart persistence are user-reported as completed; device-reboot recovery is now user-verified; tampered-file rejection remains pending.