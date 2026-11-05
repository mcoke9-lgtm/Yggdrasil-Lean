# Automation action preflight

## Purpose

Project-owned automation actions must check the executor, action, evidence freshness, capacity arithmetic, and authorization immediately before offering or invoking the action. A prompt instruction is not a deterministic action gate.

## Implemented gate

`BuilderActionPreflight` is a provider-neutral deterministic gate. Each observation identifies:

- executor and action;
- capability state: AVAILABLE, BLOCKED, or UNKNOWN;
- observation time and maximum evidence age;
- optional maximum/current/remaining capacity values;
- authorization state: GRANTED, NOT_REQUIRED, DENIED, or UNKNOWN;
- a non-empty evidence reference.

The gate fails closed:

- BLOCKED evidence or denied authorization returns BLOCKED;
- missing, stale, future-dated, unknown, or inconsistent evidence returns RECHECK_REQUIRED;
- a full capacity record (remaining = 0) returns BLOCKED;
- only fresh AVAILABLE evidence with acceptable authorization and consistent capacity returns PROCEED.

`execute` evaluates immediately before invoking the supplied action. The action is never invoked for BLOCKED or RECHECK_REQUIRED results. The policy is pure and covered by deterministic JVM tests.

## Android scheduler integration

The Android app's existing project-owned scheduler uses WorkManager and `SchedulerWorker`. `BuilderActionBoundaries` provides named adapters that are called by the production code, enforce the exact expected action ID, and are directly exercised by JVM tests:

1. Startup registration of the periodic WorkManager poll passes through `installPeriodicPoll`. The app remains not-ready while the WorkManager enqueue Operation is pending. `schedulerReady` becomes true only in the asynchronous completion listener after the Operation succeeds; failure or interruption leaves it false. The listener does not block the UI thread.
2. The “Add GitHub Actions monitor” dialog is not offered unless `offerMonitorRegistration` passes against a fresh local readiness observation.
3. Saving a monitor passes through `registerMonitor`; the actual local database insert is inside the guarded action, and the user pressing Save supplies the authorization for that registration.
4. Each enabled monitor's GitHub request passes through `checkGithubMonitor` after validating its persisted configuration. The worker is constrained to a connected network and the app declares INTERNET permission. A GitHub request outcome, not the local preflight, establishes whether GitHub actually responded.

A blocked/recheck result at monitor execution is recorded in scheduler activity history and the GitHub request is not attempted. The named adapters are narrow, testable seams around these production action boundaries; they do not constitute a full Android UI/WorkManager instrumentation test.

## Explicit limitations

- The local readiness observation proves only that local app prerequisites are present and, after the asynchronous enqueue callback succeeds, that WorkManager accepted the periodic-work registration. It does **not** prove continuous background execution, GitHub reachability, valid repository access, or notification delivery. The generic gate supports capacity arithmetic, but production Android observations currently provide no live maximum/current/remaining capacity values; no live capacity measurement or executor-selection logic is implemented.
- GitHub reachability is established only by a real request result. A failure remains a failure; the gate does not retry by switching to ChatGPT scheduled tasks.
- This code governs actions initiated by this Android application. It cannot intercept ChatGPT's separate host-level `SuggestAutomation` UI or task-creation service. No supported integration point for that host action boundary is present in this repository.
- The historical ChatGPT task-capacity observation remains a separate, provider-specific record. This implementation neither consumes nor frees ChatGPT task slots.
- The independent scheduler remains UNKNOWN as a deployed, persistent service until deployment and end-to-end operation are independently verified. The Android app's source implementation and local WorkManager setup are not evidence of a separate always-on server.

## Verification

`BuilderActionPreflightTest` covers the pure policy plus the named production adapters for periodic-poll installation, monitor-offer gating, monitor database registration, and GitHub monitor requests. Negative tests assert that blocked, stale, denied, or unknown evidence prevents the wrapped side effect; a positive GitHub-boundary test asserts one invocation after fresh authorization. The adapters also reject an observation whose action ID does not match the boundary. These are JVM adapter tests, not Android instrumentation or on-device tests. Exact-head CI and on-device behavior must still be checked against the resulting commit before calling this implementation verified.
