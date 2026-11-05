# Nine Builder Android acceptance checklist

A passing Gradle unit-test job does not establish that the APK installs, Android Keystore works on a physical device, or a model endpoint is reachable.

## Repository CI
- [ ] Run `./gradlew --no-daemon :app:testDebugUnitTest`.
- [ ] Confirm protocol, journal, coordinator, prompt/parser, provider, transport, and secure-settings tests pass.
- [ ] Record exact commit SHA and workflow run URL.

## Android build and installation
- [ ] Build with `./gradlew --no-daemon :app:assembleDebug`.
- [ ] Install on Android 8.0+ (API 26 or newer).
- [ ] Confirm main screen opens and Nine Builder conversation screen is reachable.
- [ ] Confirm a new thread, saved-thread reopen, and history retention after app restart.

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
