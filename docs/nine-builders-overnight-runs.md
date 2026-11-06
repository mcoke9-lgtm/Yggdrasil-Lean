# Nine Builders overnight runs

The Android app now has a scheduled-run path built on WorkManager. Scheduling first persists a `SCHEDULED` checkpoint containing the run/thread identity and approved-prompt digest; the worker changes it to `RUNNING` only when execution begins. The eight-hour deadline starts at that transition, not at queue time. A queued run can therefore be durably cancelled before its first worker invocation.

## Behavior

- The user enters a task and explicitly approves a one-time disclosure dialog.
- The schedule is bound to the SHA-256 digest of the exact task prompt and all nine role-specific provider IDs, endpoints, and model IDs.
- The run uses a new dedicated conversation thread. It does not include an existing conversation's history.
- WorkManager schedules the first cycle immediately or after a user-selected delay of up to 24 hours, subject to network connectivity and Android scheduling policy.
- Each cycle invokes all nine configured Builder roles in the existing coordinator order. Baldr runs after the other eight and uses its separately configured route.
- After a completed cycle, the next cycle is queued for approximately 15 minutes later, until the eight-hour maximum window is reached.
- The task prompt is encrypted with an Android Keystore AES-GCM key before it is stored in WorkManager input. Run metadata stores only the prompt digest.
- Each completed cycle is a durable checkpoint. If the host stops during a cycle, the next invocation marks the run interrupted and refuses to replay the partial cycle automatically.
- An ongoing foreground notification provides a Stop action.

## Android execution limits

This uses WorkManager's foreground execution with the Android `specialUse` foreground-service type. Android 14+ requires the corresponding manifest permission and subtype declaration. Store-distribution policy may require review of this special-use declaration.

A scheduled WorkManager job is not a hard real-time alarm. Actual start and interval can be delayed by network availability, Doze, battery policy, device vendor behavior, or OS restrictions. A force-stopped app will not run scheduled work until the user launches it again. No code can guarantee that a Galaxy S21 will remain awake and execute uninterrupted for eight hours without device testing and checking its power-management settings.

## What is required before a real overnight run

- All nine role-specific routes must be configured and reachable.
- Baldr's route must be distinct by endpoint and model ID from every other configured role.
- The user must grant notification permission so the persistent notification and Stop action are visible.
- The app must be installed and the foreground-service behavior tested on the actual Galaxy S21.
- A first controlled short run should verify the full route set, provider timeouts, journal persistence, cancellation, process recovery, and battery behavior before attempting an overnight run.

## Recovery boundary

A completed cycle is safe to continue from because its full set of Builder turns is already committed to the dedicated journal thread. A host interruption during a cycle is not automatically replayed: some role replies may already have been committed, and the app does not yet have per-role idempotent resume tokens for an incomplete coordinator round. The run is marked `INTERRUPTED` for inspection. This avoids silently duplicating model calls or side effects.

No device installation, provider credential entry, route configuration, or overnight execution is performed by this code change.
