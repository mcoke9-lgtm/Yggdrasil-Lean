# Builder Key Steward — Design and Safety Gate

**Status:** Android implementation built; disposable-key creation and saved-keystore verification reported successful on the user's Galaxy S21 / Android 15; edge-case unit tests and disposable-key CI signing/repeat-build checks verified. Production migration remains gated.
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
5. After explicit confirmation, encode the exact verified keystore bytes for GitHub secret transfer, with a 48 KB size check, Android sensitive-clipboard marking, and a scheduled clipboard clear.

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

- [x] Standalone app builds without changing Builder Host or Scheduler behavior.
- [x] Disposable-key generation and PKCS#12 export/import round-trip pass in CI.
- [x] Bouncy Castle-generated disposable PKCS#12 is loaded by the JDK provider and used by Gradle to sign a test APK.
- [x] `apksigner` verifies the APK and the APK signer fingerprint matches the PKCS#12 certificate.
- [x] Wrong password and altered keystore data are rejected by unit tests.
- [x] Fingerprint remains identical after export/import.
- [x] User-initiated Base64 export has explicit confirmation, a 48 KB encoded-length guard, sensitive clipboard metadata, and a scheduled clear.
- [x] Repeat APK assembly with the same disposable signing identity produces the same verified signer fingerprint (CI run `42017325650`, commit `37bb6bb89c2cb797a3ecbb680d0927ec43d0d102`).
- [x] Missing expected PKCS#12 alias is rejected by unit test and CI run `42017325650`.
- [x] Export-size validator accepts exactly 36 KiB and rejects 36 KiB + 1 byte; unit test passed in CI run `42017325650`.
- [x] Expired-certificate rejection unit test passed in CI run `42017325650`.
- [x] Dedicated Base64-encoded 48 KiB boundary test added; exact limit accepted and limit + 1 rejected in CI test step on commit `4139407a05a6ab216bb44350915e5de78533d0eb`.
- [x] Full CI workflow passed on commit `4139407a05a6ab216bb44350915e5de78533d0eb` (run `42017725787`): signed APK assembly, repeat assembly with the same disposable identity, both APK signature checks, stable signer fingerprint, and artifact upload all succeeded.
- [x] User reports disposable keystore creation succeeded on Galaxy S21 / Android 15.
- [x] User reports the saved keystore was reopened and verified successfully on Galaxy S21 / Android 15.
- [ ] Permanent key is created only after phone testing and all remaining release gates.
- [ ] At least two independently retained encrypted backups are confirmed by the user before migration.

## Explicit non-goals

No remote key management, cloud upload, password recovery, private-key display, general password manager, or replacement of the Builder Host/Scheduler apps.

## Next phase — phone and backup readiness

The disposable CI signing gate is complete. This does not authorize production-key creation or migration. Keep the permanent-key gate closed until the following evidence is recorded:

1. **Device rehearsal (disposable identity only):** on the Galaxy S21 / Android 15, create a disposable encrypted PKCS#12, save it through the Storage Access Framework, reopen it, and confirm the certificate SHA-256 fingerprint matches the creation result.
2. **Negative-path rehearsal (disposable identity only):** verify that a wrong password and a modified copy are rejected. Confirm cancellation of Base64 export does not leave pending data available for a later copy.
3. **Clipboard rehearsal (disposable identity only):** explicitly confirm export, verify the copied value is intended for the GitHub secret field, and observe the scheduled clear while the app remains alive. If the app is stopped or killed, clear the clipboard manually; the in-app timer is not a durable background guarantee.
4. **Independent encrypted backups:** before any production migration, make at least two encrypted PKCS#12 backups in independently retained locations. Reopen and verify each backup separately; record that both certificate fingerprints match the source. Keep the password separate from the backup files. Two filenames or two folders on the same device do not count as independent backups.
5. **Production change review:** only after the previous gates are confirmed should a separate, explicitly reviewed change configure production signing secrets. Never put passwords or keystore contents in repository files, issue comments, chat, logs, or commit history.
6. **Migration boundary:** do not uninstall or disable Builder Host/Scheduler, rotate existing production identities, or change production signing configuration as part of this foundation PR. Treat migration as a separate, reversible plan with a verified recovery path.

### Evidence status

- CI disposable signing, repeat assembly, APK signature verification, and stable fingerprint: **verified** by run 42017725787 on commit 4139407a05a6ab216bb44350915e5de78533d0eb.
- Disposable keystore creation and saved-file reopening on Galaxy S21 / Android 15: **user-reported**; not independently observed by CI.
- Production-key creation, two independently retained encrypted backups, and production migration: **not done; gated**.
- Static review of the current Android source found no reason to expand this PR into production signing or host/scheduler changes. This is not a formal security audit or a substitute for the device rehearsal above.


## CI APK installation compatibility

- Debug/CI APKs use the application ID `org.yggdrasil.keysteward.ci` via the debug build-type suffix. The production/reserved application ID remains `org.yggdrasil.keysteward`.
- This permits the disposable-CI-signed debug APK to be installed alongside an existing production installation instead of attempting an in-place update with a different signing certificate.
- The CI/debug package is a separate test installation. It does not update, migrate, replace, or validate the signing identity of the production installation. Keep the production app and its data untouched during this rehearsal.
- Verify the artifact's final package ID and signing certificate in CI before asking the user to install it. Do not claim the side-by-side installation is validated until the user confirms it on-device.


## Distinguishing the CI installation

- The debug/CI APK overrides the launcher label to **Builder Key Steward CI** through `key-steward/src/debug/AndroidManifest.xml`.
- The production manifest label remains **Builder Key Steward**. The CI package ID remains `org.yggdrasil.keysteward.ci`; the production package ID remains `org.yggdrasil.keysteward`.
- This visible label is intended to make the disposable test installation identifiable on-device without changing or replacing the production app.
