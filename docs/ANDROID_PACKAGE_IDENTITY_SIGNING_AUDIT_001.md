# Android Package Identity and Signing Audit 001

**Date:** 2026-11-05  
**Repository:** `mcoke9-lgtm/Yggdrasil-Lean`  
**Branch:** `independent-builder-runtime-foundation-001`  
**Status:** Source-level root cause identified; on-device certificate comparison pending.

## Executive finding

The repeated Android message, "App not installed as package conflicts with an existing package," is consistent with an update being signed by a different certificate from the already-installed package.

The repository contains a common signing-process defect across the two applications:

- Builder Host has a persistent signing configuration, but it is conditional on four GitHub Actions secrets. When they are absent, Gradle uses the default debug signing identity. CI explicitly skips distributing the Builder Host APK when persistent signing is absent, but locally built debug APKs can still be produced.
- Key Steward has no persistent signing configuration. Its CI job builds and uploads the default debug APK on each run. GitHub-hosted runners are ephemeral; their default debug keystores are not a stable release identity.

This is a strong source-level explanation for why successive builds can fail to update earlier installations. It is not yet a certificate-level proof of the exact Key Steward installation failure.

## Package identities

| Application | Gradle applicationId | Namespace |
|---|---|---|
| Builder Host | `org.yggdrasil.builderhost` | `org.yggdrasil.scheduler` |
| Key Steward | `org.yggdrasil.keysteward` | `org.yggdrasil.keysteward` |

These package IDs are distinct. The two applications do not conflict with each other merely because both are installed. Android's message indicates a conflict with an already-installed package identity or signing relationship; exact installed package state still needs on-device inspection.

## Evidence inspected

- `app/build.gradle`: Builder Host persistent signing requires `BUILDER_KEYSTORE_PATH`, `BUILDER_KEYSTORE_PASSWORD`, `BUILDER_KEY_ALIAS`, and `BUILDER_KEY_PASSWORD`. The debug build type selects the persistent configuration only when all four environment variables are present.
- `key-steward/build.gradle`: no signing configuration is declared; application ID is `org.yggdrasil.keysteward`.
- `key-steward/src/main/AndroidManifest.xml`: standalone launcher application; no custom shared UID.
- `.github/workflows/independent-builder-runtime.yml`: Key Steward CI builds and uploads `key-steward-debug.apk` without a persistent keystore. Builder Host artifact upload is conditional on persistent signing secrets.
- User screenshot dated 2026-11-05 shows multiple Key Steward APKs downloaded and Android's package-conflict installation failure for "Builder Key Steward".
- Existing Builder Host recovery record associates a previously installed build with debug certificate SHA-256 `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A`. This is the best available evidence for that historical Builder Host build, not direct live-device inspection.
- Key Steward artifact from CI run `42054633700` has APK SHA-256 `7cafe494514f89a13e89c8d79b65a4ab3b6753ac9aca8eb840e1c9618a15a813`; its signer fingerprint has not yet been captured in this audit.

## Confirmed vs inferred

**Confirmed**
- The user reports earlier installations of both applications.
- The user screenshot shows the current Key Steward package-conflict message.
- Builder Host and Key Steward have distinct application IDs.
- Key Steward has no stable signing configuration in source.
- Builder Host's stable signing configuration is conditional and the required secrets have not been configured.
- The current Key Steward CI workflow distributes an APK built with default debug signing.

**Strong inference, pending certificate comparison**
- A later debug APK was signed with a different certificate from the installed copy, causing Android to reject it as an update.

**Unknown**
- The signer certificate fingerprint of the currently installed Key Steward.
- The signer fingerprint of every earlier Key Steward APK the user installed.
- Whether the current downloaded APK and installed package differ only by signing certificate or also by version / package metadata.
- Direct live-device package state.

## Safe next steps

1. Stop installing successive debug APKs.
2. Do not uninstall either application, clear data, or use package-manager bypasses.
3. Obtain signer SHA-256 fingerprints for the installed-version source APK (if available) and each candidate replacement APK.
4. If the installed signer private key is unavailable, treat any change to a new permanent signer as a controlled migration, not an in-place update.
5. Before migration, export and independently verify each app's user data / journal and test restoration.
6. Establish a stable signing identity for every distributable application. CI must fail closed or mark output as non-distributable when stable signing is not configured. Do not upload temporary debug-signed APKs under names that imply they are update-ready.
7. Verify two independent builds with the same keystore produce the same certificate fingerprint before distributing updates.

## Safety boundary

No application was uninstalled, updated, cleared, or otherwise changed by this audit. No signing secrets or private key material were accessed. No device-side certificate comparison has yet been performed.
