# Nine Builder Android acceptance checklist

This checklist separates repository CI from device and provider acceptance. A passing Gradle unit-test job does not establish that the APK installs, that Android Keystore works on a physical device, or that a model endpoint is reachable.

## Repository CI
- [ ] Run `./gradlew --no-daemon :app:testDebugUnitTest`.
- [ ] Confirm all protocol, journal, coordinator, prompt/parser, provider, transport, and secure-settings tests pass.
- [ ] Record the exact commit SHA and workflow run URL.

## Android build and installation
- [ ] Build the debug APK with `./gradlew --no-daemon :app:assembleDebug`.
- [ ] Install on a supported Android 8.0+ device (API 26 or newer).
- [ ] Confirm the main scheduler screen opens and the Nine Builder conversation screen is reachable.
- [ ] Confirm the app remains usable after rotation and process/activity recreation.
- [ ] Confirm a new thread can be created, a saved thread reopened, and conversation history retained after app restart.

## Credential and endpoint safeguards
- [ ] Configure a localhost model without a token; verify settings can be saved and removed.
- [ ] Verify hosted HTTP, non-local cleartext HTTP, URL user-info, query strings, and fragments are rejected.
- [ ] Configure a test token; verify the UI masks it, the token survives app restart, and removal clears the saved credential.
- [ ] Confirm no credential appears in logs, conversation turns, exported conversation history, or error messages.
- [ ] Confirm no request occurs merely by saving provider settings or opening the conversation screen.
- [ ] Confirm first send displays endpoint/model disclosure and Cancel sends nothing.
- [ ] Confirm Allow and send sends only the selected thread context and selected Builder prompts to the disclosed endpoint.
- [ ] Confirm redirects are not followed and response-size/time limits are enforced.

## Nine Builder conversation
- [ ] Run a mock-provider round with all nine roles and verify nine distinct role prompts.
- [ ] Verify later Builders receive earlier replies in the same thread.
- [ ] Verify invalid model output is rejected and reported without corrupting the journal.
- [ ] Verify a provider failure preserves the user turn and any earlier successful Builder turns.
- [ ] Verify bounded peer handoffs and unanswered recipients are reported accurately.
- [ ] Verify Builders cannot create a DECISION turn.

## Live provider acceptance
- [ ] Only after mock-provider acceptance, configure a provider endpoint and explicitly approve its disclosure.
- [ ] Use non-sensitive test content.
- [ ] Confirm provider-specific retention, data-use, and privacy terms before sending real project material.
- [ ] Record endpoint type (hosted/local), model identifier, device/OS, test date, and observed result. Never record the API token.
