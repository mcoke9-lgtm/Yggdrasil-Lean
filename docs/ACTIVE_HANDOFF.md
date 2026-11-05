# Active Handoff — Yggdrasil Builder Host

**Last updated:** 2026-11-05  
**Repository:** `mcoke9-lgtm/Yggdrasil-Lean`  
**Active branch:** `independent-builder-runtime-foundation-001`  
**Protected branches:** `main` and `android-scheduler-host-prototype-001` — do not modify.  
**User device:** Samsung Galaxy S21, Android 15.  
**App:** Yggdrasil Builder Host; application ID `org.yggdrasil.builderhost`.  
**Separate Scheduler app:** preserve; do not uninstall or modify.

## Current verified state

- User installed the latest Builder Host APK after Android reported a package conflict with the previous installation.
- Before uninstalling the old Builder Host, user confirmed Journal Integrity passed and exported a fresh snapshot to Downloads.
- User installed the new build, ran **Test recovery safeguards (safe)** and reported PASS.
- User restored the snapshot and confirmed Journal Integrity again reported **chain verified**.
- Physical-device results are user-reported; no direct device inspection.
- Scheduler app was not to be modified.
- Latest recovery verification record: `docs/ANDROID_BUILDER_GALAXY_S21_RECOVERY_VERIFICATION.md`.

## Current engineering task: stable APK signing

The existing CI workflow built a debug APK without supplying a persistent keystore. Each ephemeral GitHub runner can therefore generate a different default debug key. This is the leading explanation for Android's package-conflict message; certificate comparison is still required before calling it proven.

**Changes just prepared on the active branch:**
- Gradle now uses a persistent signing configuration when all four environment variables are supplied.
- CI now accepts four GitHub Actions repository secrets:
  - `BUILDER_KEYSTORE_BASE64`
  - `BUILDER_KEYSTORE_PASSWORD`
  - `BUILDER_KEY_ALIAS`
  - `BUILDER_KEY_PASSWORD`
- CI prints APK signer certificate details with `apksigner verify --print-certs`.
- If no signing secrets are configured, CI warns and builds with a temporary debug key. Such an APK is not a stable update.
- If only some signing secrets are configured, CI fails rather than silently producing an inconsistent build.

**Critical migration fact:** the private key for the APK currently installed on the phone cannot be reconstructed from the signed APK. Once a new permanent key is configured, one final uninstall-and-restore may be required to move to it. After that migration, preserve the same keystore and secrets for every future release. Do not ask the user to uninstall again until the permanent-key build is ready, verified, and backed up.

## Next actions

1. Verify the latest commits and CI run on the active branch.
2. Give the user simple, safe instructions for creating/storing the long-lived keystore and adding the four GitHub repository secrets. Never ask them to paste private key material or passwords into chat.
3. Once secrets are configured, run CI and record the APK certificate SHA-256 digest.
4. Compare signer digest across two builds to prove stable signing.
5. Only then plan the final phone migration, with a fresh journal snapshot and integrity check first.
6. Keep the import/confirmation UX issue open; user previously observed a single action button.
7. Later test offline behavior. Do not claim local inference, independent agents, arbitrary app control, offline voice or background execution without implementation and verification.

## Context-window / new-chat protocol

The user is experiencing repeated “context window full” notices and has to start new chats. Start future chats by reading this file first, then the current Android continuity checkpoint and the Galaxy S21 recovery verification record. Do not make the user restate the project history. Carry forward the active branch and protected-branch rules.

## Working boundaries

- User is director and decision authority.
- Keep Builder roles distinct in presentation, but be transparent that they are currently coordinated roles simulated by one ChatGPT model, not nine independently running agents.
- Do not claim a repository write, build, installation, or test succeeded until the tool/device evidence confirms it.
- Keep the user's exported backup untouched unless the user explicitly authorizes otherwise.
- Do not change protected branches or the separate Scheduler app.
- Code-production-rate analysis is parked until a meaningful amount of new code accumulates or the user reopens it.


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
