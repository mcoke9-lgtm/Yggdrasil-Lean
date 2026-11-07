# Nine Builder Process Isolation Experiment 001

**Status:** implementation committed; Android emulator execution pending.
**Branch:** `nine-builder-process-isolation-experiment-001`
**Parent:** `nine-builder-independent-checkpoint-experiment-001`
**Purpose:** move beyond nine database files in one test process and verify nine separately scheduled Android application processes.

## What is implemented

- Nine private Android service components, one for each Builder role.
- Each component is declared with a distinct `android:process` name (`:builder_huginn` through `:builder_baldr`).
- All nine components share a small service implementation but have distinct role identity and distinct SQLite journal filenames.
- Each service reports its role, operating-system PID, and Android process name.
- Each service accepts append requests only when the supplied speaker identity matches its own role.
- The device instrumentation test binds all nine services concurrently, checks that nine distinct PIDs and process names are returned, appends one role-owned checkpoint to each, and reads each checkpoint back from that role's service.
- The Android Actions workflow now includes an emulator-backed `:app:connectedDebugAndroidTest` step in addition to the existing JVM suite.

## Test boundary and security boundary

This is an Android multi-process experiment, not nine autonomous AI agents.

The nine services:
- run as separate Android processes on one application installation and one device/emulator;
- share the application's UID and package-level permissions;
- use local SQLite files in the same application data sandbox;
- do not yet establish authenticated communication between different devices;
- do not provide nine independently scheduled model-inference loops;
- do not establish independent human/agent authorization or cryptographic identity;
- do not yet implement cross-process conflict reconciliation or network partition recovery.

A process boundary is stronger than separate database files, but it is not a device, account, ownership, or authority boundary. The existing `BuilderRuntimeService` remains the canonical conversation host in its own process; this experiment creates role-local process services and journals alongside it. It does not replace or merge that host.

## Acceptance criteria

1. All nine service components bind successfully on an Android emulator/device.
2. All nine return the expected distinct role.
3. The nine reported PIDs are unique while services are held bound concurrently.
4. The nine Android process names are unique.
5. Each role can append and read its own role-identified checkpoint.
6. The existing JVM test suite remains green.
7. The emulator instrumentation test is executed by GitHub Actions and its result/logs are inspected.

## Integrity checkpoint

- **Count:** nine roles, nine service components, nine declared process names, nine local databases.
- **Role mapping:** each component's subclass returns one exact `BuilderRole`; append rejects another role's identity.
- **Omissions/duplicates:** one physical device and one application UID; no claim of nine independent devices, credentials, or agents.
- **Evidence status:** source and workflow changes committed; actual Android emulator execution pending.
- **Correction rule:** role-local records remain append-only; conflicts are not automatically resolved by this experiment.
- **Persistence:** isolated branch and draft PR only; no merge, deployment, or change to the default branch.
