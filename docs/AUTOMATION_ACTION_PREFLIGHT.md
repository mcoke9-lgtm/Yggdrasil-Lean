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

The Android app's existing project-owned scheduler uses WorkManager and `SchedulerWorker`. The gate is connected at these boundaries:

1. Startup registration of the periodic WorkManager poll is preflighted.
2. The “Add GitHub Actions monitor” dialog is not offered unless the local scheduler preflight passes.
3. Saving a monitor repeats the preflight and encloses the actual local database insert inside `execute`; the user pressing Save supplies the authorization for that registration.
4. Each enabled monitor's GitHub check is enclosed inside `execute` after validating its persisted configuration. The worker is constrained to a connected network and the app declares INTERNET permission. A GitHub request outcome, not the local preflight, establishes whether GitHub actually responded.

A blocked/recheck result at monitor execution is recorded in the scheduler activity history and the GitHub request is not attempted.

## Explicit limitations

- The local readiness observation proves only local app/WorkManager wiring, declared network permission, and the persisted enabled-monitor state. It does **not** prove GitHub reachability, valid repository access, continuous background execution, or notification delivery.
- GitHub reachability is established only by a real request result. A failure remains a failure; the gate does not retry by switching to ChatGPT scheduled tasks.
- This code governs actions initiated by this Android application. It cannot intercept ChatGPT's separate host-level `SuggestAutomation` UI or task-creation service. No supported integration point for that host action boundary is present in this repository.
- The historical ChatGPT task-capacity observation remains a separate, provider-specific record. This implementation neither consumes nor frees ChatGPT task slots.
- The independent scheduler remains UNKNOWN as a deployed, persistent service until deployment and end-to-end operation are independently verified. The Android app's source implementation and local WorkManager setup are not evidence of a separate always-on server.

## Verification

`BuilderActionPreflightTest` covers fresh availability, blocked state, full capacity, stale evidence, unknown capability, unknown/denied authorization, inconsistent capacity, future timestamps, and the key no-invocation guarantee for blocked or stale results. CI and on-device behavior must still be checked against the resulting commit before calling this implementation tested or verified.
