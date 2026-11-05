# Incident: Host Automation Capacity Gate Was Not Applied

- Incident ID: HOST-AUTOMATION-CAPACITY-GATE-001
- Date: 2026-11-05
- Status: OPEN_DIAGNOSIS
- Severity: Methodology / control-boundary failure
- Related work: PR #35, independent scheduler prototype branch `independent-scheduler-prototype-001`, checkpoint `2ce21c5`

## Observed behavior

The user requested a one-time scheduled check of Android CI completion for GitHub Actions run `42108797159`, associated with PR #35 head `4baa010f3ae57a3e299f15a5aa546e969c1b854e`.

The assistant displayed an automation suggestion and attempted to create a ChatGPT scheduled task. The task service rejected creation because all three available task slots were occupied (3/3). The assistant then suggested freeing a slot.

## Expected behavior

The known operational constraint and the existing project direction should have been applied before suggesting or attempting a host automation:

1. Do not offer or attempt a ChatGPT scheduled task when the host task capacity is known to be full.
2. Do not recommend deleting, consolidating, or freeing user tasks as the default workaround.
3. Recognize the independent Nine Builders scheduler as the intended project-owned alternative.
4. Check whether that scheduler is actually deployed, reachable, and authorized for this workload. Its prototype status or historical tests do not establish live availability.
5. If the independent scheduler is not yet available, state the exact gap and use an honest non-scheduled continuation path only when the user requests it. Do not imply that a future check has been scheduled.
6. Record the failed route and preserve the original task intent and run identity.

## Important attribution and boundary finding

Kvasir/Covesser was not actually invoked in the ChatGPT automation offer/create path. The Nine Builders runtime is a separate Android/project implementation; PR #35 is still draft and unmerged, and its interruption protocol does not intercept ChatGPT's host-level `SuggestAutomation` or automation-creation actions.

Therefore this incident must not be described as proof that a running Kvasir process examined the request and failed. The assistant failed to route the decision through Kvasir or an equivalent pre-action operational gate. The existing Kvasir prompt instruction alone cannot intercept a separate host tool/action path.

This exposes a missing integration boundary: operational constraints must be checked by the component that offers or invokes an external action, not only placed in a model prompt or a separate application runtime.

## Evidence

- User context already established that ChatGPT's scheduled-task capacity was 3/3 and that the independent scheduler was chosen to avoid making ChatGPT capacity the controlling constraint.
- Earlier scheduler checkpoint: branch `independent-scheduler-prototype-001`, commit `2ce21c5`; historical 12/12 test result was reported, but live deployment, service execution, and GitHub access remain unverified.
- ChatGPT task-creation service response in this conversation: capacity limit, current count 3, plan limit 3; creation failed.
- PR #35 remains draft/open/unmerged. Its runtime documentation explicitly states that its coordinator does not intercept arbitrary external actions.
- The Android CI check the user asked to monitor was run `42108797159`; at the time of this incident it was still in progress. The Lean workflow on the same head completed successfully as run `42108797160`.

## Suspected causes

1. The host automation suggestion path was used without a preflight against known task capacity.
2. The Nine Builders' operational gate is implemented only inside a separate project runtime and was not connected to the ChatGPT host action path.
3. The independent scheduler is recorded as a first-class alternative but is still `UNKNOWN` for live deployment/access, so it could not be truthfully claimed as available.
4. The assistant treated the automation offer as the default follow-up even though the user had already selected an independent-scheduler strategy.

## Required corrective work

- Add an explicit host-action preflight rule: known blocked capacity suppresses the host automation offer and creation attempt.
- Treat an unobservable host capacity as unknown; do not assume the action is available. If the user explicitly requests a task, check capacity once and report the result without retrying through another host task slot.
- Add an external-action capability record identifying the executor, capacity source, freshness, authorization, and whether the gate is enforceable at that action boundary.
- Keep Kvasir's role as a reviewer/steward, but do not rely on prompt text as a substitute for deterministic enforcement.
- Define a project-owned scheduler adapter contract for one-time CI checks: run ID, repository, expected head SHA, read-only GitHub access, due time, retry policy, result delivery, and terminal states.
- Before using that adapter, independently verify deployment, persistence, service health, GitHub access, and notification delivery. Until then, label it NOT AVAILABLE / UNKNOWN.
- Add regression tests for: known full host capacity; stale/unknown capacity; independent scheduler unavailable; independent scheduler verified available; no host-task fallback; no claim of scheduling without confirmed creation.

## Current state and limits

- The host task creation was NOT created.
- No independent scheduler job was created or executed.
- No task was deleted or modified.
- No new credentials, permissions, deployments, or device changes were made.
- This record documents the incident; it does not itself implement the corrective gate or adopt a new project policy.

## Acceptance criteria for closure

1. A known-full host capacity blocks the host automation suggestion and create action before either occurs.
2. An unavailable or stale capacity source cannot be treated as available.
3. The selected independent scheduler is preferred only after its live capability is verified.
4. If neither executor is verified, the system states the exact gap and does not claim a scheduled follow-up.
5. An end-to-end test demonstrates that the gate is applied at the same boundary that offers or invokes the external action.
6. Baldr independently reviews the evidence and the user explicitly adopts the correction before it becomes project practice.
