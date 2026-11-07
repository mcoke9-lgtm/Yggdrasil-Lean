# Nine Builder Process Isolation Experiment 001 — Design Gate

**Status:** Architecture inspected; implementation proposed, not yet built or device-verified.
**Branch:** `nine-builder-process-isolation-design-001`
**Parent:** `nine-builder-independent-checkpoint-experiment-001`
**Purpose:** Move from nine separately persisted role stores in one test process toward nine independently scheduled Android worker processes, while preserving role identity, local continuity, and user authority.

## 1. What exists today

The Android manifest declares `BuilderRuntimeService` with `android:process=":builder_runtime"`. This places the native runtime in a process separate from the main application process.

That service currently:
- owns one persistent runtime identity;
- owns one canonical conversation journal;
- advertises all nine Builder role names;
- handles IPC through one Messenger endpoint; and
- does not perform model inference.

The nine-store checkpoint test adds nine distinct SQLite files, but opens them sequentially within one Robolectric process. It is not a multi-process test.

## 2. Proposed process shape

Introduce one role-bound Android service component per Builder, each with a fixed manifest process name:

| Builder | Proposed process suffix | Local responsibility |
|---|---|---|
| Huginn | `:builder_huginn` | Evidence and source review |
| Muninn | `:builder_muninn` | History, checkpoints, recovery |
| Kvasir | `:builder_kvasir` | Constraints and capability limits |
| Mari | `:builder_mari` | Interfaces and relationships |
| Höðr | `:builder_hodr` | Runtime operations and execution |
| Týr | `:builder_tyr` | Permissions and authorization checks |
| Loki | `:builder_loki` | Interruption and failure recovery |
| Odin | `:builder_odin` | Coordination and integration |
| Baldr | `:builder_baldr` | Independent verification |

Use nine thin, final service classes that inherit from a shared worker base. The role identity must be fixed by the service class, not supplied by an untrusted Intent extra. Each worker should reject requests that attempt to make it speak or act as another role.

Each worker receives:
- a stable role identity;
- a role-specific private journal/database;
- a process-start identity and health response;
- a versioned, narrow IPC contract;
- a bounded local capability set; and
- an append-only checkpoint history.

The existing runtime service remains the coordinator and recovery interface initially. It must not impersonate the nine workers or claim that all are alive merely because their names are registered.

## 3. Process and persistence boundaries

- Each role service runs under a distinct Android process name.
- Each role owns its own database file; no two role services write the same SQLite database.
- The coordinator requests snapshots or event batches through IPC and appends received records to a separate reconciliation journal.
- A disconnected or stopped worker keeps its local records and is reported as unavailable, not as active.
- The coordinator may preserve received records but may not silently rewrite the source worker's history.
- Reconciliation is an appended account of what was received and compared; it is not an automatic consensus mechanism.
- A worker's local checkpoint is not proof that its permissions are still current.

All processes in one Android application still share the app's Linux UID and application sandbox. This is process isolation, not a strong security boundary against compromise of the application UID, nor a separate physical node.

## 4. Identity, permissions, and safe IPC

- Keep all worker services `exported=false` in the first implementation.
- Bind only from the application/coordinator; do not expose a public network listener.
- Derive the role identity from the service implementation, never from caller-provided role text.
- Record process name, role, runtime instance ID, event ID, timestamp, protocol version, and evidence status on every checkpoint.
- Validate message size, schema version, event identity, and allowed operation before writing.
- Use append-only writes and reject duplicate event IDs.
- Separate permission to preserve a record from permission to execute an action.
- Require fresh coordinator/user authorization for consequential actions; offline workers may research, draft, or preserve bounded local findings only.
- Do not store credentials or signing secrets in ordinary checkpoint bodies or logs.

The first multi-process prototype should use Android's private Binder/Messenger transport. Network transport, remote node identity, cryptographic event signatures, and multi-device synchronization are later gates, not implied by local process separation.

## 5. Required device-level experiment

Add Android instrumentation coverage, not only Robolectric unit tests. Run on an emulator or physical Android device.

The first acceptance sequence:
1. Start and bind all nine role services.
2. Query each worker's health endpoint.
3. Verify nine distinct process names and nine fixed role identities.
4. Append one role-owned checkpoint to each worker's private database.
5. Stop or kill two selected workers while the other seven remain responsive.
6. Confirm the two unavailable workers are reported unavailable and the other seven continue within their own limits.
7. Restart the two workers and recover their original checkpoints.
8. Introduce different findings from two roles and preserve both as unresolved.
9. Reconcile snapshots into the coordinator journal without rewriting either local journal.
10. Verify duplicate delivery does not create duplicate records and that role spoofing is rejected.

The instrumentation test must inspect actual process names using Android runtime process information. A list of nine service declarations or nine database filenames alone is not evidence of nine running processes.

## 6. CI and verification plan

The existing `android-unit-tests.yml` workflow runs `./gradlew --no-daemon :app:testDebugUnitTest`. It does not run Android instrumentation tests.

Before claiming process isolation is tested:
- add an Android instrumentation test source set and runner;
- add emulator provisioning to GitHub Actions (or identify a user-controlled device test path);
- run the instrumentation test on a supported API level;
- capture test results and process identity evidence; and
- separately test process death/restart and database recovery.

Keep the JVM test suite as the fast validation layer. Do not treat it as a substitute for emulator/device execution.

## 7. Implementation order

1. Define the fixed role-worker service contract and message envelope.
2. Implement a shared base service plus nine final role-bound service classes.
3. Declare nine private manifest services with distinct process names.
4. Give each worker an isolated database and append-only checkpoint API.
5. Add instrumentation runner and a nine-process health/identity test.
6. Add kill/restart and local checkpoint recovery coverage.
7. Add coordinator reconciliation with duplicate suppression and visible unresolved differences.
8. Verify on emulator CI before extending to networked nodes or model inference.

Do not add model execution, external APIs, remote networking, or consequential autonomous actions in this milestone. The goal is to establish independent process lifecycle and memory boundaries first.

## 8. Relationship to current work

- Disconnect/reconnect test PR #48 remains a separate predecessor.
- Independent checkpoint test PR #49 remains a separate predecessor and has successful Android JVM CI.
- This design branch is a new, isolated planning checkpoint. It does not change either PR, merge into the parent, or alter the default branch.
- No nine-worker service implementation or device execution is claimed by this document.

## 9. Integrity checkpoint

- **Count:** nine named roles, nine proposed process names, ten acceptance steps.
- **Role mapping:** each process is bound to exactly one declared Builder role.
- **Omissions/duplicates:** no model inference, remote nodes, cryptographic authentication, or user-device execution is claimed.
- **Evidence status:** existing single runtime process is verified by manifest; nine role-specific worker processes are proposed, not implemented.
- **Correction rule:** if Android lifecycle, resource limits, or test evidence contradict the design, revise the design without erasing earlier checkpoints.
- **Persistence:** design recorded on an isolated branch; no merge, deployment, or permission expansion.
