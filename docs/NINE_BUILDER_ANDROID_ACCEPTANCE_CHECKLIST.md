# Nine Builder Android acceptance checklist

A passing Gradle unit-test job does not establish that the APK installs, Android Keystore works on a physical device, or a model endpoint is reachable.

## Persistent APK signing — required before distribution

**Never distribute an APK signed with a runner-generated debug key.** The Android package name is `org.yggdrasil.builderhost`; Android requires updates to use the same signing certificate as the installed app. A temporary GitHub runner key changes between runs and causes Android's conflicting-package/signature error.

Create one long-lived signing keystore on a trusted computer. Keep the keystore and passwords backed up securely; loss of this key prevents future in-place updates. Do not commit the keystore, passwords, or encoded keystore to the repository.

Example using JDK `keytool` (choose and securely retain your own passwords):
```sh
keytool -genkeypair -v \
  -keystore yggdrasil-builder-release.jks \
  -alias yggdrasil-builder \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w 0 yggdrasil-builder-release.jks
```
On macOS, use `base64 < yggdrasil-builder-release.jks | tr -d '\\n'` instead of `base64 -w 0`.

In GitHub repository **Settings → Secrets and variables → Actions**, create these four repository secrets:
- `BUILDER_KEYSTORE_BASE64`: the single-line Base64 output of the keystore.
- `BUILDER_KEYSTORE_PASSWORD`: keystore password.
- `BUILDER_KEY_ALIAS`: `yggdrasil-builder` (or the alias chosen above).
- `BUILDER_KEY_PASSWORD`: password for that key alias.

The workflow runs tests and compiles without these secrets, but deliberately does **not** publish an APK. If only some secrets are configured, the workflow fails. Once all four are configured, the APK is signed with the persistent key and the workflow prints its certificate digest. Compare that digest across releases; it must remain identical.

The signing key is an update identity, not an API credential. Keep an offline backup and restrict repository secret access to trusted maintainers.

## One-time device migration warning

The already-installed app was signed by a temporary CI debug key. It cannot be updated in place with the new persistent key. **Do not uninstall until any locally stored Builder conversation history you want to keep has been exported or otherwise backed up.** Then uninstall the old app and install the first APK signed with the permanent key. Later APKs signed with that same key can update in place, provided their versionCode increases.

The application version is now 0.2.0 / versionCode 2 to move beyond the previously distributed 0.1.0 / versionCode 1 package. This does not bypass Android's signing-certificate check; the one-time migration is still required.

## Repository CI
- [ ] Run `./gradlew --no-daemon :app:testDebugUnitTest`.
- [ ] Confirm protocol, journal, coordinator, prompt/parser, provider, transport, and secure-settings tests pass.
- [ ] Record exact commit SHA and workflow run URL.
- [ ] Confirm no distributable APK artifact is uploaded until persistent signing secrets exist.

## Android build and installation
- [ ] Build with `./gradlew --no-daemon :app:assembleDebug`.
- [ ] Install on Android 8.0+ (API 26 or newer).
- [ ] Confirm main screen opens and Nine Builder conversation screen is reachable.
- [ ] Confirm a new thread, saved-thread reopen, and history retention after app restart.
- [ ] Verify certificate SHA-256 digest matches the previous persistent release.

## Credential and endpoint safeguards
- [ ] Configure a localhost model without a token; verify settings save and removal.
- [ ] Verify hosted HTTP, non-local cleartext HTTP, URL user-info, query strings, and fragments are rejected.
- [ ] Verify a hosted token is masked and retained only when provider ID and endpoint remain unchanged.
- [ ] Change provider ID or endpoint with token blank; verify the old token is not reused.
- [ ] Confirm no credential appears in logs, conversation turns, exports, or error messages.
- [ ] Confirm saving settings and opening the conversation screen make no model request.
- [ ] Confirm first send displays endpoint/model disclosure; Cancel sends nothing.
- [ ] Confirm Allow and send sends only the selected thread context and selected Builder prompts.
- [ ] Confirm redirects are not followed and response size/time limits are enforced.

## Nine Builder conversation
- [ ] Run a mock-provider round with all nine roles and verify nine distinct role prompts.
- [ ] Verify later Builders receive earlier replies in the same thread.
- [ ] Verify invalid model output is rejected without corrupting the journal.
- [ ] Verify provider failure preserves the user turn and earlier successful Builder turns.
- [ ] Verify bounded peer handoffs and unanswered recipients are reported accurately.
- [ ] Verify Builders cannot create a DECISION turn.

## Live provider acceptance
- [ ] Only after mock-provider acceptance, configure a provider and explicitly approve its disclosure.
- [ ] Use non-sensitive test content.
- [ ] Confirm provider retention, data-use, and privacy terms before sending project material.
- [ ] Record endpoint type, model identifier, device/OS, date, and observed result. Never record the API token.

## Build toolchain pin
- Gradle Wrapper version: 8.9.
- Distribution: `https://services.gradle.org/distributions/gradle-8.9-bin.zip`.
- Distribution SHA-256: `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`.
- Wrapper JAR is sourced from the official Gradle `v8.9.0` release tag and is validated by the Gradle setup action.

## JVM test environment
- Unit tests use the standalone JSON-java implementation for JVM execution rather than Android's unmocked framework stubs.
- Credential-destination matching is tested as a pure configuration rule; Android Keystore encryption/decryption remains a device/runtime acceptance check.

## CI validation checkpoint — 2026-11-05
- Validation run: [Builder Conversation Journal #42030534184](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42030534184).
- Tested source commit: `8a8785c506de3bba5676c2960000fe5bddd0d256`.
- Result: 79 unit tests passed; 0 failures, 0 errors, 0 skipped.
- The passing suite includes a nine-role mock HTTP-adapter conversation: nine role-specific requests, accumulated thread context, parsed responses, and ten persisted turns (one user turn plus nine Builder turns).
- Android debug APK assembly and artifact upload succeeded.
- The validated application/test source changes were adopted into `independent-builder-runtime-foundation-001` at `6d39bdd3b4bc29fdf67f96cdea6072912e0d0a2d`; the six validated source/test/documentation file blobs match the validation branch.
- Not yet verified: installation on a physical device, Android Keystore runtime behavior, actual loopback HTTP server exchange, and any hosted provider request.
- No live model endpoint or API credential was configured or used.
