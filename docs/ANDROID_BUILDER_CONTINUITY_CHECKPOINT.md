# Android Builder Runtime — Phone-First Checkpoint

**Branch:** `independent-builder-runtime-foundation-001`
**Latest signing-support code commit:** `0e9cc14ac2c4144c2d21c6c184747582ca3c9222`
**Latest CI workflow commit:** `067d7273f16fa9ba2ff25e41d37228057e8d7a08`
**Cross-chat handoff commit:** `459c34ef02dd90e83cb0bad26ff1d78479a1380e`
**Android application ID:** `org.yggdrasil.builderhost`
**Display name:** Yggdrasil Builder Host
**Device:** Samsung Galaxy S21 / Android 15 (user-reported).

## Implemented runtime foundation

- Nine stable Builder role identifiers and immutable registry.
- Versioned, transport-neutral Builder message envelope.
- Separate SQLite continuity journal, independent of scheduler database.
- Unique event IDs, append-only events, SHA-256 linked hash chain and full-chain verification.
- Transactional idempotent message receipts.
- Versioned JSON snapshot export and validated restore.
- Restore checks format/version, event ordering, event IDs, hash links, duplicate IDs and receipt references before transactionally replacing local records.
- Android document-picker export and import; import has a 10 MB input limit, validation and confirmation before replacement.
- In-app **Test recovery safeguards (safe)** control: creates a deliberately altered snapshot in memory, attempts restore, checks that the live journal remains unchanged and chain-valid, and defensively restores the original snapshot if unexpected mutation is detected. It does not edit the user's external backup.
- Robolectric recovery tests: snapshot round-trip, duplicate receipt protection, tamper rejection without mutation, unsupported version rejection without mutation.
- Deterministic local command router for bounded note capture and journal checks; unsupported requests are identified rather than falsely reported as completed.
- Local Mari-to-Muninn handoff demonstration inside one app process, not nine independent agents.

## Physical-device verification — user-reported

On the Samsung Galaxy S21, Android 15:

1. Original Builder Host Journal Integrity reported chain verified.
2. User exported a fresh snapshot and confirmed it was present in Downloads.
3. Android rejected update installation with a package-conflict message. User uninstalled the original Builder Host (not the separate Scheduler app) and installed the new APK.
4. New app opened and displayed **Test recovery safeguards (safe)**.
5. User ran the recovery-safety test and reported **PASS**.
6. User restored the fresh snapshot.
7. User ran Journal Integrity after restore and reported **chain verified**.

This establishes user-reported successful snapshot recovery and journal-chain verification after reinstall. It is not independent inspection of the phone.

Detailed record: `docs/ANDROID_BUILDER_GALAXY_S21_RECOVERY_VERIFICATION.md`.

## APK signing issue and current changes

The previous CI workflow built debug APKs without a persistent keystore. Ephemeral GitHub runners can generate different default debug signing keys. This is the leading explanation for the package conflict; a direct certificate comparison has not yet been completed.

Committed support:
- Gradle commit `0e9cc14ac2c4144c2d21c6c184747582ca3c9222`: uses a persistent signing configuration when all required environment variables are supplied.
- Workflow commit `067d7273f16fa9ba2ff25e41d37228057e8d7a08`: reads four GitHub Actions repository secrets, warns when none are configured, fails if only some are configured, and prints signer certificate information using `apksigner`.
- Required repository secrets:
  - `BUILDER_KEYSTORE_BASE64`
  - `BUILDER_KEYSTORE_PASSWORD`
  - `BUILDER_KEY_ALIAS`
  - `BUILDER_KEY_PASSWORD`

**Not yet completed:** creating the long-lived keystore, configuring repository secrets, and verifying CI with the persistent key. No claim that the new signing workflow has passed CI.

**Migration warning:** the private key used to sign the APK currently installed on the phone cannot be recovered from that APK. Switching to a new permanent key may require one final uninstall-and-restore. Do not ask the user to uninstall again until the permanent-key APK is built, verified, and backed up. Preserve the keystore and secrets for all future builds.

## Next gates

1. Create and securely retain the long-lived signing keystore; add the four repository secrets without sharing private key material or passwords in chat.
2. Run CI; verify the signer SHA-256 digest. Build again and compare the digest to prove signing stability.
3. Plan one final phone migration only after CI and signing verification, with a fresh snapshot and pre-uninstall integrity check.
4. Clarify import preview/confirmation UX; user previously observed a single action button.
5. Test offline behavior and capability boundaries before selecting local inference or voice components.

## Scope and integrity boundaries

- Protected `main` and `android-scheduler-host-prototype-001` remain untouched.
- Separate Scheduler app must remain untouched.
- Journal hash chain is tamper-evident, not cryptographic authentication against an attacker able to rewrite the database.
- No arbitrary app control, external messaging, purchasing, local inference, offline speech, timed background Builder execution or nine independently running agents is claimed.
- User is director and decision authority.
- Code-production-rate analysis remains parked until meaningful new code accumulates or user reopens it.
- For new chats, read `docs/ACTIVE_HANDOFF.md` first; it is the concise cross-chat entry point.


## Android device self-reporting bridge — 2026-11-05

- User-confirmed target: Samsung Galaxy S21+ variant SM-G996U, Android 15.
- Source implementation added: `AndroidDeviceInventory` collects model/device, Android/API level, supported ABIs, processor count, total/available RAM, and data-volume total/available storage.
- Runtime health response carries the versioned inventory over the existing local, non-exported Messenger IPC. The conversation screen displays the detected device details automatically.
- Privacy boundary: no serial number, Android ID, IMEI, phone number, location, Wi-Fi identifier, account data, installed-app inventory, or filesystem contents. No new Android permission and no cloud upload.
- A dedicated refresh message is also defined. The current UI obtains the report on runtime connection.
- Added Robolectric assertions for inventory schema, capability declaration, platform values, and excluded identifiers.
- Implementation and documentation are committed on this branch. Android CI run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42051226557
- **Current status:** CI was observed in progress at the last check; build/test result not yet known. No new APK is ready or installed. No physical-device inventory has yet been read by the new code.
- **Next gate:** wait for CI. If it passes, inspect the generated APK and signer certificate. Do not ask the user to uninstall or replace the currently installed Builder Host until stable signing is verified and a fresh backup/migration plan is ready.
- **Interoperability still pending:** portable cross-host capability envelope and authenticated sync between phone, free cloud and NUC. This change is local self-reporting only.


### CI result update — 2026-11-05

- Android workflow run `42051226557` completed with conclusion `success`.
- `:app:testDebugUnitTest` and `:app:assembleDebug` completed successfully; the APK signature-verification command also passed.
- The persistent-signing configuration step intentionally continued without a distributable build because the four `BUILDER_*` signing secrets were not configured. The artifact-upload step was therefore skipped; GitHub reports zero artifacts for this run.
- Consequently, this run verifies compilation, unit tests, and that the locally generated debug APK has a valid Android signature. It does **not** verify the established persistent signing identity, nor provide an APK for installation or upgrade.
- The source implementation is included in the tested workflow commit `60184a4087576ff356151981c3b3e18d3bcd88cd`. The branch subsequently advanced to `9f6da91dc5aefd3d5b21af3a9f9c977d6988a1ea` with checkpoint documentation only; no Android source changed after the tested commit.
- Do not install a newly generated debug-signed package over the existing Builder Host. First establish the existing installed package's signer identity and recover/verify the matching persistent signing key through repository secret configuration. Never place keystore passwords or private key material in chat, source control, or logs.
