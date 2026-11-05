# Android Builder Runtime — Continuity and Command Checkpoint

**Branch:** `independent-builder-runtime-foundation-001`
**Current source commit:** `53523547721ddf357b9c07862cede310fa06e28b`
**Device target:** Samsung Galaxy S21 / Android 15 (user-reported). No APK has yet been installed from this branch on the physical phone.

## Implemented

- Nine stable Builder role identifiers and immutable registry.
- Versioned transport-neutral message envelope.
- Separate SQLite continuity journal, independent of the scheduler database.
- Unique event IDs, append-only events, SHA-256 linked hash chain, chain verification and transactional idempotent message receipts.
- Versioned JSON snapshot export and validated restore.
- Restore validates format/version, event sequence, event identity, duplicate IDs, every hash link, and processed-message references before transactional replacement.
- Android document-picker export to a user-selected JSON file.
- Android document-picker import with 10 MB input cap, format preview, explicit user confirmation before replacing local memory, and full validation before mutation.
- First local Mari-to-Muninn handoff.
- Robolectric tests for snapshot round-trip, duplicate receipt protection, tampered snapshot rejection without mutation, and unsupported snapshot version rejection.
- Deterministic Builder command router for the currently implemented local capabilities:
  - “remember that …” / “record note …” / “save note …” / “tell Muninn …”
  - “check memory” / “verify memory” / “memory status”
  - unsupported instructions are explicitly declined rather than falsely reported as completed.
- Android console UI wired to that limited router.

## Verified

- Android CI passed at commit `071e7c7a0572fa54ccb3125d5c484ee95da25807`. The unit-test and Android compile step passed; debug APK upload passed.
  - Run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42002389771
  - Artifact: `yggdrasil-builder-runtime-debug-apk`, ID `13403131759`, approximately 2.37 MB, retained for 14 days from creation.
  - This run includes the Robolectric recovery tests and document-picker export/import implementation.
- The command-router unit tests and Android UI integration are committed after that verified build. Latest Android CI run for current source: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42002603400 (pending at checkpoint update).
- No physical device installation or on-device test has occurred.

## Boundaries

The command router is deterministic and intentionally limited; it is not a general language model. The local Mari-to-Muninn handoff runs inside one app process; it is not nine independent agents. No arbitrary app control, external messaging, purchasing, destructive operations, local inference, offline speech, or timed background Builder execution is claimed.

The SHA-256 chain is tamper-evident, not authenticated against an attacker who can rewrite the entire database. Snapshot JSON is not encrypted; encrypted backup and key management remain future work.

## Next gates

1. Verify CI for the command-router UI integration.
2. Install the latest verified APK on the Galaxy S21 and test:
   - launch and basic UI;
   - “remember that …” writes a note;
   - “check memory” verifies the journal;
   - export to a user-selected file;
   - clear app data only after a separate backup decision, then import into a clean journal (or use a safe test database);
   - tampered file is rejected without replacing existing records.
3. Build the next conversational layer only after phone tests, with capability permissions and clear unsupported-action responses.
4. Benchmark local inference, battery, heat, memory and offline behavior on the actual phone before choosing a model or voice stack.

## Integrity

One active independent runtime branch; protected `main` and scheduler branch untouched; scheduler and continuity data separate; nine roles registered; import replacement requires confirmation and validated snapshot; CI and physical-device states distinguished.