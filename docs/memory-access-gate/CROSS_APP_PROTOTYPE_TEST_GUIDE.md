# Memory Gate cross-application prototype

This branch contains three separate Android applications:

- `memory-gate` — owns the private SQLite store and protected Binder service.
- `memory-client` — allow-listed client. It requests an approved token and one fixed fictional record.
- `memory-adversary` — non-allow-listed package. It attempts the same Binder calls and should receive no content.

## Build

From the repository root, with Android SDK 35 and a compatible Gradle installation:

```sh
./gradlew :memory-gate:assembleDebug :memory-client:assembleDebug :memory-adversary:assembleDebug
```

Run JVM tests:

```sh
./gradlew :memory-gate:testDebugUnitTest
```

These commands are instructions only; they have not yet been executed in this project workflow.

## Signing requirement

The Gate and trusted client must be signed with the same certificate because the service declares a signature-level permission. The Gate additionally checks that the Binder caller UID maps to exactly one installed package, that the package name is the expected trusted client, and that the signatures match.

The adversarial module deliberately uses a different package name. A debug build from the same build environment may share the debug signing certificate with the other modules; in that case it tests the Gate's package allow-list and per-transaction caller check. A separately signed adversarial build is still required to demonstrate Android's signature-permission denial for a mismatched certificate.

Do not use a shared UID. The Gate rejects UIDs associated with more than one package.

## Manual test sequence

1. Install Gate and trusted client builds signed with the same certificate.
2. Open Memory Gate and review the displayed single-record, ten-minute approval.
3. Approve the request. The Gate checks that the trusted package exists, has an isolated UID, and shares the Gate signing certificate.
4. Open Trusted Memory Client and request the approved fictional record.
5. Confirm only the one permitted record is returned.
6. Install and run the adversarial client. Confirm that it receives neither a grant token nor record content.
7. Repeat with an adversarial build signed with a different certificate; confirm the Android signature permission blocks binding.

Only fictional fixtures are included. Do not add real personal data to these tests.

## Current evidence boundary

Source files and manifests are committed and read-back checked. GitHub Actions currently runs the repository's existing Lean checks; those jobs are not Android Gradle compilation, Android unit tests, instrumentation tests, or device security tests. No installation or runtime test is claimed until separately recorded.
