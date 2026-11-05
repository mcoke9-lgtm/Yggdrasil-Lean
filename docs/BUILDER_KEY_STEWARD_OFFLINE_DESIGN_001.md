# Builder Key Steward — Offline Android Signing Key Utility

**Status:** Source implemented; CI and physical-phone verification pending.  
**Module:** `:key-steward`  
**Application ID:** `org.yggdrasil.keysteward`  
**Target:** Galaxy S21+ SM-G996U, Android 15.

## Purpose

Create the permanent Builder Host signing identity on the user's phone, under the user's control. The utility is separate from the existing Builder Host and Scheduler apps so it can be installed without replacing either.

## Key format and parameters

- PKCS#12 keystore (`.p12`).
- Alias: `yggdrasil-builder-host`.
- RSA 3072-bit key.
- SHA256withRSA self-signed certificate.
- 25-year certificate validity.
- Password minimum 16 characters.
- PKCS#12 uses the same password for the keystore and private-key entry; use the same value for GitHub secrets `BUILDER_KEYSTORE_PASSWORD` and `BUILDER_KEY_PASSWORD`.
- The Gradle Builder Host signing configuration explicitly selects `PKCS12`.

## Privacy and security boundaries

- Manifest declares no Internet or network-state permission.
- No HTTP client, telemetry, logging of key material, background upload, or app-private persistent key storage.
- Key generation and PKCS#12 verification happen locally.
- User chooses the export destination with Android's Storage Access Framework. Choose a secure local folder, not a shared/cloud-synced location.
- A separate offline backup is required before configuring GitHub.
- Base64 is copied to the local clipboard only on explicit action and marked sensitive for Android clipboard previews. User should paste directly into GitHub's protected secret field, then return to this utility and clear the clipboard.
- Password is not saved by this utility. The user must retain it in a trusted password manager or another secure user-controlled method.
- The private keystore file and Base64 value must never be pasted into chat, committed to source control, or uploaded as a workflow artifact.
- CI builds only the Key Steward APK. It does not generate or receive the private signing key.

## GitHub Actions secrets

After the user creates and safely backs up the keystore, configure these four repository secrets in GitHub Settings → Secrets and variables → Actions:

1. `BUILDER_KEYSTORE_BASE64` — copied from the Key Steward app.
2. `BUILDER_KEYSTORE_PASSWORD` — the chosen password.
3. `BUILDER_KEY_ALIAS` — `yggdrasil-builder-host`.
4. `BUILDER_KEY_PASSWORD` — same chosen password for PKCS#12.

Do not send secret values in chat. The connected GitHub integration cannot create or read repository secrets.

## Acceptance gates

1. CI compiles Key Steward and passes PKCS#12 round-trip, fingerprint, and wrong-password tests.
2. CI confirms the Key Steward manifest has no Internet/network permission and source has no network client/logging reference.
3. User installs the standalone utility on the phone; this does not modify the Builder Host or Scheduler.
4. User generates the keystore on the phone and exports a local backup plus a second offline backup.
5. User adds four protected GitHub secrets directly in GitHub.
6. CI produces a Builder Host APK and reports the certificate SHA-256 digest.
7. A second build produces the same digest, proving stable signing.
8. Before any future Builder Host migration, export a fresh journal snapshot and verify its chain. Do not uninstall or clear data before the signed APK is ready and verified.

## Current installed APK signer evidence

The APK artifact linked from recovery verification run `42005258590` has signer subject/issuer `CN=Android Debug, O=Android, C=US`, serial `01`, SHA-256 `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A`. This is not the permanent key and its private key cannot be recovered from the APK.

## Not yet verified

- The app has not yet passed CI.
- No Key Steward APK has been installed on the phone.
- No permanent keystore has been generated.
- No GitHub secrets have been configured.
- No persistent-signed Builder Host APK has been produced.
- No further uninstall, restore, or migration has been performed.


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
