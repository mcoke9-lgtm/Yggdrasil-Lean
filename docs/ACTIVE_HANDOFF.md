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


## Signing identity investigation — 2026-11-05

- Located and downloaded the exact CI artifact referenced by the Galaxy S21 recovery verification record: workflow run `42005258590`, artifact `13403234570`, built from commit `0a23267dc51deba4d5fab814b39bd3af51177e19`.
- Inspected the APK signing block and extracted its signer certificate. Certificate subject/issuer: `CN=Android Debug, O=Android, C=US`; serial `01`; SHA-256 fingerprint: `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A`.
- The recovery verification record associates this artifact with the APK installed during the user's prior one-time migration. This is the best available signer evidence for the installed build; direct inspection of the live phone is not available.
- This is the standard Android debug certificate, not a permanent Builder signing identity. The private key cannot be reconstructed from the APK. The old artifact can establish the certificate fingerprint but cannot be used to sign future builds.
- The current workflow's `apksigner verify --print-certs` step verifies APK signature validity and prints its signer digest; it does not by itself establish continuity with the installed APK. Persistent CI secrets remain absent, and no persistent-signed APK has been produced.
- **Migration status:** the user previously exported a fresh journal snapshot, uninstalled the original package after an Android package-conflict, installed the new Builder Host, passed the safe recovery test, restored the snapshot, and confirmed the journal chain. Those are user-reported results. A further change to a permanent signer would require another controlled migration unless the permanent certificate can be made to match the installed certificate; the matching private key is unavailable.
- Do not ask the user to uninstall or clear app data. Before any future signer migration, protect the current journal with a fresh export and verify the chain. The user has already experienced one migration; avoid repeating it unless essential.
- No signing secrets can be read or written through the currently connected GitHub integration. The user must create/store the permanent private key in a user-controlled secure location and enter the four GitHub Actions secrets through GitHub's protected settings. Never request secret values in chat.


## Builder Key Steward implementation checkpoint — 2026-11-05

- Added separate Android module `:key-steward`, application ID `org.yggdrasil.keysteward`; it does not replace or modify the existing Builder Host or Scheduler package.
- The app generates a password-protected PKCS#12 file locally (RSA-3072, alias `yggdrasil-builder-host`, SHA256withRSA certificate, 25-year validity), reloads and verifies it, and reports the certificate SHA-256 digest.
- User explicitly chooses the export location through Android's document picker. The app offers a second export, can load and verify a backup, and copies Base64 only on explicit request. It marks the clipboard item sensitive and provides a targeted clear operation.
- The app declares no Internet/network-state permission, disables Android backup, prevents screenshots, avoids logging and app-private key persistence, and clears in-memory key bytes when the activity is destroyed. Password is not stored; PKCS#12 uses the same password for store and key entry.
- The workflow includes a static offline-boundary check and builds/uploads only the Key Steward APK, never a generated signing key. APK artifact is a temporary installer only, not key material.
- Builder Host Gradle signing now explicitly selects PKCS#12; CI decodes the user-created keystore only after the four protected GitHub secrets exist.
- CI attempt 1 failed on duplicate Bouncy Castle multi-release OSGI metadata; added a packaging exclusion.
- CI attempt 2 failed because Android's `ClipDescription.setExtras` expects `PersistableBundle`, not `Bundle`; corrected this. Later UI/security refinements added retryable backup export, clipboard cleanup after app restart, and screenshot blocking.
- Latest complete-source workflow run `42054633700` was queued at the time of this checkpoint. Run `42054477744` was still in progress on the earlier commit `d2249c8026141b8ec5146ed0ee307b944eae5f24`. No successful Key Steward build/artifact has yet been verified.
- No keystore has been generated; no private key or password has been sent to GitHub; no repository signing secrets have been configured; no phone installation or change has occurred.
- **Next gate:** verify the latest CI run for commit `f285fb984049a7fd88c3939da07c7012390255dd`; download only the Key Steward APK artifact; inspect its merged manifest/package and install it as a separate app; test PKCS#12 generation, export, reload, password rejection and fingerprint on the actual Galaxy S21+ Android 15. Do not configure GitHub secrets or migrate Builder Host until these phone tests pass.


### Latest CI status refresh — 2026-11-05 15:26 UTC

- Latest complete-source Android workflow: run `42054633700`, head commit `f285fb984049a7fd88c3939da07c7012390255dd`.
- Run is in progress. Both `android-build` and `key-steward-build` are executing their Gradle test/build steps.
- Offline manifest/source boundary check completed successfully.
- No Key Steward artifact is available yet. No installation or key generation has occurred.
