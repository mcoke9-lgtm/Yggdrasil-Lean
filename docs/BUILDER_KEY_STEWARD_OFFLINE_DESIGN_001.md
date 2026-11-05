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
