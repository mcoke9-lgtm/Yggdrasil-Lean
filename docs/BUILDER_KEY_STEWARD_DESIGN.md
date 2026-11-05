# Builder Key Steward — Design and Safety Gate

**Status:** Foundation design; implementation not yet verified.
**Target:** Standalone Android app for the Samsung Galaxy S21 / Android 15.
**Application ID:** `org.yggdrasil.keysteward` (reserved for this module).
**Repository branch:** `builder-key-steward-foundation-001`.

## Purpose

Provide a phone-only, offline-capable way to create, protect, back up, inspect, and prepare an APK signing identity for GitHub Actions. The app is deliberately separate from Builder Host and Scheduler.

## Scope (first usable release)

1. Generate an RSA signing keypair and self-signed X.509 certificate.
2. Store the key in an encrypted PKCS#12 keystore.
3. Save/export through Android's Storage Access Framework; no broad storage permission.
4. Re-open and verify the exported keystore, alias, private-key presence, certificate dates, algorithm, and SHA-256 certificate fingerprint.
5. Encode the verified keystore in memory for a user-initiated GitHub secret transfer, with a size check and explicit sensitive-clipboard warning.

## Security decisions

- No INTERNET permission, analytics, telemetry, or automatic upload.
- Never display or log private key bytes, passwords, or Base64 keystore contents.
- No plaintext key export. The only backup is encrypted PKCS#12.
- Use a maintained cryptographic implementation (Bouncy Castle) for X.509 and PKCS#12. Do not implement cryptographic primitives or certificate encoding ourselves.
- PKCS#12 generally uses one password for the store and private-key entry. The existing CI interface has separate store/key password variables; configure both to the same secret value unless interoperability tests demonstrate a safe, compatible alternative.
- The Android Keystore is not the export path: non-exportable private keys cannot be supplied to the current GitHub Actions APK-signing workflow.
- Do not create the user's permanent key until a disposable-key round-trip and CI signing test pass.
- Do not ask the user to uninstall Builder Host until the permanent-key CI build and repeat-build certificate fingerprint checks pass and the user's journal backup is verified.

## Compatibility

The current repository uses Android Gradle Plugin 8.7.3, Gradle 8.9, compile/target SDK 35, and min SDK 26. Gradle's signing DSL exposes `storeType`; the eventual CI signing configuration must explicitly select `PKCS12` if this format is adopted. That change is deferred until the app can produce and verify a compatible file.

Bouncy Castle Java 1.86 is the current release shown by the vendor at the time of this design. Pin exact artifact versions and verify Android/D8 compatibility in CI before relying on them. Official release page: https://www.bouncycastle.org/download/bouncy-castle-java/

## Release gates

- [ ] Standalone app builds without changing Builder Host or Scheduler behavior.
- [ ] Disposable-key generation and PKCS#12 export/import round-trip pass.
- [ ] Wrong password, altered file, missing alias, expired/not-yet-valid certificate, and oversize secret cases fail safely.
- [ ] Fingerprint remains identical after export/import.
- [ ] GitHub Actions can sign a disposable test APK with the exported PKCS#12; repeat build has the same signer digest.
- [ ] Permanent key is created only after the above.
- [ ] At least two independently retained encrypted backups are confirmed by the user before migration.

## Explicit non-goals

No remote key management, cloud upload, password recovery, private-key display, general password manager, or replacement of the Builder Host/Scheduler apps.
