# Android-local Living Tree intake staging

**Status:** implemented in source; build and device verification pending.

## What this adds

The Android host provides a dedicated "Stage Living Tree intake queue" action. It opens Android's document picker, reads a user-selected JSON file (maximum 5 MB), validates the YIM-001 queue version 2 structure, displays the queue ID and packet counts, and requires confirmation before writing to a private SQLite database named `yim_intake_staging.db`.

The staging database is separate from:
- Builder conversation history;
- the Builder append-only event journal; and
- the canonical YIM-001 audited Roots store.

Each staged row retains the exact submitted queue text, the packet identity and parsed packet content, capture time, source kind, language, original placement status, and staging time. A repeat import of the same queue and packet with the same packet content is idempotent. Reuse of the same queue/packet identity with changed packet content is rejected. Staging all packets is one SQLite transaction.

## Deliberate boundary

**Staging is not Roots import.** This feature does not:
- assign primary or contextual Tree locations;
- update the canonical audited memory store;
- mark a queue packet as imported;
- contact a network service or cloud provider;
- write to Builder conversation or event-journal history.

The UI explicitly tells the operator that staged packets are not yet in Roots.

## Validation

The adapter accepts only `YIM-001-Work-Intake-Queue` version 2, exact queue and packet field sets, typed string identities and source fields, ISO-8601 timestamps with offsets, unique packet IDs within a queue, valid pending/imported placement state, and the true source-preservation marker. It enforces a 5 MB file limit and a 1,000 packet limit.

## Source files

- `app/src/main/java/org/yggdrasil/scheduler/intake/IntakeQueueValidator.java`
- `app/src/main/java/org/yggdrasil/scheduler/intake/IntakeStagingStore.java`
- `app/src/main/java/org/yggdrasil/scheduler/MainActivity.java`
- `app/src/test/java/org/yggdrasil/scheduler/intake/IntakeStagingTest.java`

## Remaining verification

- Android Gradle build and unit tests.
- Actual device test using the existing phone intake queue.
- Restart persistence and repeat-import behavior on device.
- A separate Roots adapter with format-parity tests, atomic audited writes, exact Tree-node validation, backup, read-back, and provenance verification.

No APK has been built or installed by this change. The pull request remains draft and unmerged.
