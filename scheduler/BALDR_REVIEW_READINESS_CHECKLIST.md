# Baldr Review Readiness Checklist

**Status:** Proposed checklist; no independent Baldr review has been performed.  
**Applies to:** The Nine Builders' Baldr review path and PR #43 implementation.  
**Companion contract:** [BALDR_INDEPENDENT_REVIEW_CONTRACT.md](./BALDR_INDEPENDENT_REVIEW_CONTRACT.md)  
**Purpose:** Establish evidence and authorization gates before any separately governed review is attempted. This checklist does not authorize credentials, live provider calls, host activation, spending, deployment, merge, or user adoption.

## How to use

For each item, record **NOT STARTED**, **IN PROGRESS**, **VERIFIED**, **BLOCKED**, or **NOT APPLICABLE**, plus an evidence reference, reviewer, and date. A checked box without a reference is not verification. Preserve UNKNOWN where evidence is unavailable; do not promote a claim from configuration alone.

## Gate 1 — Scope and authorization

- [ ] The review target is identified by exact task, run, commit, and artifact references.
- [ ] The review question and required evidence package are bounded; unrelated user data is excluded.
- [ ] The user has explicitly authorized the specific live review action, provider, account/deployment, data transfer, and any cost before execution.
- [ ] The authorization identifies what the reviewer may inspect and what it may not change.
- [ ] No review result is treated as user adoption, merge approval, deployment approval, or host activation.

**Block if:** the scope, data transfer, authorization, or cost is unclear.

## Gate 2 — Reviewer and control-plane identity

- [ ] The reviewer route is documented separately from each of the eight peer routes.
- [ ] Provider, account/organization, deployment, endpoint identity, requested model, and region (if relevant) are recorded as distinct fields.
- [ ] Evidence for provider/account/deployment identity comes from a trusted provider record or independently controlled administrative source, not only local labels or configuration.
- [ ] The person or process controlling reviewer credentials and deployment is identified, with conflicts disclosed.
- [ ] Shared ownership, infrastructure, gateways, routing layers, training dependencies, and other common control points are disclosed.
- [ ] Missing or unverifiable identity evidence remains **UNKNOWN**; it is not inferred from a different URL, API key string, provider label, or model name.

**Block if:** the claimed separation cannot be supported by evidence. A different route alone supports, at most, route separation.

## Gate 3 — Invocation and evidence integrity

- [ ] All eight peer outcomes are present, ordered, and linked to exactly one durable turn when successful; failed, interrupted, and skipped states are explicit.
- [ ] The exact task prompt, review rubric, contract version, evidence references, and operational constraints are included in the immutable input manifest.
- [ ] The manifest digest and exact serialized request-body digest are recorded before the provider request.
- [ ] Invocation-start is durably recorded before the call; the response is durably stored before completion is recorded.
- [ ] Invocation ID, input digest, response digest, durable reviewer-turn reference, timestamps, and any provider request ID are cross-checked.
- [ ] Provider-issued receipts are distinguished from host-generated metadata; optional or absent provider request IDs are not represented as provider attestation.
- [ ] An ambiguous outcome is marked for manual reconciliation; no automatic replay is attempted.

**Block if:** the request cannot be reconstructed, receipt linkage fails, or the outcome is ambiguous.

## Gate 4 — Review isolation and contamination controls

- [ ] The reviewer's conversation/thread identity and prior context exposure are recorded.
- [ ] Any prior Baldr outputs are excluded from a clean-room review, or their presence is explicitly disclosed.
- [ ] The reviewer receives the evidence package rather than only peer conclusions.
- [ ] The reviewer is asked to independently test claims against the supplied artifacts and distinguish direct checks from reasoning critiques.
- [ ] The review records disagreements, unsupported claims, missing tests, safety concerns, and unresolved unknowns.
- [ ] The reviewer does not certify its own output; user adoption remains a separate action.

**Block if:** prior context contamination is concealed or the reviewer lacks the underlying evidence needed to assess claims.

## Gate 5 — Security, privacy, and operational safeguards

- [ ] No credential, authorization header, secret, or unnecessary personal data is written to plaintext logs or the review manifest.
- [ ] The protected vault is the source of truth for the full request, response, and receipt; plaintext records contain only allowlisted metadata.
- [ ] Access to the review package and receipt records is limited, attributable, and auditable.
- [ ] Failure, timeout, cancellation, duplicate invocation, persistence failure, and recovery behavior are tested.
- [ ] Custom task-vault implementations are governed or independently constrained; host-provided receipt claims are not treated as self-authenticating.
- [ ] No live host activation, deployment, device change, or spending occurs as an incidental consequence of review.

**Block if:** secrets or unnecessary personal data may leak, receipt provenance is self-asserted without governance, or a failure can be silently treated as success.

## Gate 6 — Findings, provenance, and adoption decision

- [ ] The final report states the strongest supported provenance level: UNVERIFIED, ROUTE_SEPARATED, INVOCATION_ATTRIBUTED, CONTROL_PLANE_DISTINCT, or INDEPENDENTLY_GOVERNED.
- [ ] The report explicitly states that none of these levels proves independent model weights or freedom from common training data/infrastructure.
- [ ] Every material finding links to its supporting evidence and is labeled proposed, implemented, tested, or verified as appropriate.
- [ ] Limitations, conflicts, common dependencies, dissent, and unresolved questions are visible.
- [ ] A separate authorized human decision records whether findings are adopted, rejected, or returned for correction.
- [ ] Any later merge, deployment, host activation, or provider use receives its own explicit authorization.

**Block if:** the report overstates independence, hides uncertainty, or treats review completion as adoption.

## Current evidence snapshot

| Area | Current state |
|---|---|
| Reference invocation receipt and encrypted vault linkage | Implemented and CI-tested |
| CI on documentation commit `ffa844e1fa4034cb3cab299f301d837f03f95453` | All 12 check-run entries passed across six check names |
| PR #43 | Open, draft, unmerged |
| Separate Baldr reviewer service | Not implemented or verified |
| Provider/account/deployment attestation | Not established |
| Independent operational governance | Not established |
| Live Baldr invocation | Not performed |
| Independent model weights | Unknown; not claimed |
| User adoption | Not performed |

## Decision rule

A review may proceed only when Gates 1–5 are verified and the required action has explicit authorization. If any gate is blocked, stop and record the missing evidence. Passing this checklist does not itself establish independence or authorize adoption; Gate 6 records the evidence-based conclusion and the separate human decision.
