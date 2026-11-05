# Nine Builders shared orchestration gate

## Purpose

Every Builder role must work from one shared, evidence-backed pre-action decision. No role may reinterpret a blocked or unknown result as permission to proceed. The gate is evaluated once for the requested capability and the same result is passed to all nine role contexts.

## Current reference implementation

- `tools/check_operational_limits.py` evaluates the operational limits register.
- `tools/nine_builders_orchestrator.py` creates the shared nine-role context and guards action dispatch.
- `tools/tests/test_operational_limits.py` tests the underlying decision gate.
- `tools/tests/test_nine_builders_orchestrator.py` tests shared role context, fail-closed dispatch, and separate authorization.

## Required sequence

1. Name the concrete capability required by the proposed action.
2. Read the canonical `app-registry/operational-limits.json` register.
3. Evaluate the exact capability with the pre-action gate.
4. Give Huginn, Muninn, Kvasir, Mari, Höðr, Týr, Loki, Odin, and Baldr the same decision, evidence source, observation date, capacity, and safe alternative.
5. If the decision is `blocked`, `recheck_required`, or `authorization_required`, do not dispatch the action. Explain the reason and choose only a genuinely feasible alternative.
6. If the decision is `proceed_with_constraints`, dispatch only within the recorded constraints.
7. If the decision is `proceed`, dispatch may occur, but any separate consent, safety, spending, installation, account-change, or destructive-action requirement still applies.
8. Record the outcome and any new authoritative evidence. Do not silently rewrite the canonical register from an unverified inference.

## Shared role responsibilities

| Builder | Primary responsibility in the gate cycle |
|---|---|
| Huginn | Find current evidence and source references |
| Muninn | Preserve continuity and compare prior observations |
| Kvasir | Interpret constraint type and meaning |
| Mari | Explain limits and feasible alternatives |
| Höðr | Challenge assumptions and blind spots |
| Týr | Enforce authorization and safety boundaries |
| Loki | Find legitimate alternatives without bypass |
| Odin | Coordinate priorities and the operating picture |
| Baldr | Verify records, timestamps, and claims |

All nine roles inherit the same stop condition. Their differing responsibilities improve scrutiny; they do not create nine independent permissions.

## Important implementation boundary

This repository currently contains a Lean formalization project and supporting analysis tools. It does **not** contain a deployed autonomous Nine Builders agent runtime or a ChatGPT conversation hook. The Python adapter is a tested reference orchestration boundary that a future runtime can call; it does not cause the nine simulated ChatGPT roles to execute independently, and it does not intercept every conversation automatically.

Until a real runtime is connected, the conversational Nine Builders workflow must apply this protocol explicitly before capability-dependent work. A CI pass verifies the adapter and tests, not live runtime adoption.

## Fail-closed behavior

- Missing or duplicate capability records: recheck.
- Missing, invalid, future-dated, or stale evidence: recheck.
- Inconsistent capacity arithmetic: recheck.
- Fresh blocked evidence: stop.
- Explicitly excluded action: authorization required.
- A separate action requiring explicit user authorization remains gated even when the capability itself is available.
- No automatic spending, task deletion, setting changes, installation, replacement, data clearing, or account-plan changes.

## Verification scope

CI runs the gate and orchestration unit tests. Passing tests demonstrate that the reference adapter passes a single shared decision to all nine role contexts and prevents dispatch for blocked or unknown capabilities. It does not demonstrate live telemetry or production deployment.
