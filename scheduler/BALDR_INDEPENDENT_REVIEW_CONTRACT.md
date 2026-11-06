# Baldr Independent Review Contract

**Status:** Proposed design contract, version 1.0.0  
**Scope:** Nine Builders final review of the other eight roles  
**Implementation status:** Not implemented as a separately attributable reviewer service  
**Authority:** The user retains final authority for consequential commitments and adoption.

## 1. Purpose

Baldr is intended to review the other eight Builders' work rather than certify the work of the same execution path that produced it. A different role prompt, endpoint string, provider label, or requested model ID is not sufficient evidence of independence.

This contract separates four claims that must never be collapsed:

1. **Configured separation:** the host configuration names a separate Baldr route.
2. **Invocation separation:** a distinct request/response invocation actually occurred and can be traced.
3. **Control-plane separation:** available evidence supports that the reviewer is operated under a distinct provider account, organization, deployment, or independently controlled service.
4. **Review independence:** the reviewer received a defined evidence package, made its own assessment, and did not merely repeat a prior Builder's conclusion.

Even all four do not prove independent model weights or freedom from common training data, infrastructure, or organizational influence. Reports must state the strongest level supported by evidence and preserve the remaining uncertainty.

## 2. Current baseline and explicit non-claims

The current reference executor:
- requires nine role routes and unique requested model IDs;
- requires Baldr's configured endpoint and requested model ID to differ from each peer;
- waits until the other eight roles have outcomes;
- checks that each successful peer outcome reference matches exactly one durable vault turn;
- sends Baldr the task prompt, prior run history, operational-limits snapshot, and typed peer outcome metadata;
- stores Baldr's response through the ordinary encrypted task-vault result path.

These controls establish **configured route separation and review-input linkage**. They do not establish a separate provider account, distinct ownership, distinct model weights, separate infrastructure, an independently controlled process, or an independently authenticated reviewer. The configured `providerId` is not currently required to differ. A provider-reported model name is metadata, not attestation.

A Baldr prompt that says “independent reviewer” is an instruction, not evidence that an independent reviewer was invoked. CI tests using mocked transports do not establish live invocation or model independence.

## 3. Required invocation record

A separately attributable Baldr invocation must produce an immutable, append-only invocation record containing:

- `reviewInvocationId`: unique, non-reusable identifier;
- `runId` and target task reference;
- `reviewerRole`: exactly `BALDR`;
- `invokedAt` and `completedAt`, with canonical timestamps;
- `inputManifestSha256`: digest of the exact review package and its ordered components;
- `peerOutcomeRefs`: the eight role-to-vault-turn references, including explicit failed or skipped states;
- `evidenceRefs`: exact source, commit, test-run, and artifact references available to the reviewer;
- `requestedProviderId`, `requestedEndpointIdentity`, and `requestedModelId`;
- `providerReportedModelId`, if returned, clearly marked provider-reported;
- `providerRequestId` or equivalent transport receipt, if available;
- `responseSha256` and durable Baldr vault-turn reference;
- `invocationStatus`: completed, failed, timed out, cancelled, or outcome-unknown;
- `provenanceStatus`: the strongest supported separation level from section 4.

Do not put credentials, authorization headers, raw secrets, or unnecessary personal data in this record. Store the full review input and response in the protected vault; store only typed references and digests in the plaintext run ledger.

If the provider call may have completed but no durable response is available, mark the outcome unknown and require reconciliation. Do not silently retry an ambiguous non-idempotent request.

## 4. Separation and provenance levels

Use explicit levels rather than a single boolean `independent=true`.

| Level | Name | Minimum evidence | Permitted claim |
|---|---|---|---|
| 0 | UNVERIFIED | No attributable invocation receipt | No independent-review claim |
| 1 | ROUTE_SEPARATED | Configured endpoint and requested model differ from peer routes | Configured route differs |
| 2 | INVOCATION_ATTRIBUTED | Distinct request/response, invocation ID, input digest, response digest, durable result, and transport receipt or equivalent host evidence | A separate Baldr invocation occurred |
| 3 | CONTROL_PLANE_DISTINCT | Trusted provider/account/organization/deployment identity evidence distinguishes the Baldr route from peer routes | Available evidence supports a distinct control plane |
| 4 | INDEPENDENTLY_GOVERNED | Evidence of separate operational control and review governance, with conflicts and common dependencies disclosed | The review path is independently governed to the stated extent |

No level proves independent model weights. A provider-reported model name, a different URL, a different API key string, or a different `providerId` supplied only by local configuration cannot by itself establish levels 3 or 4. Prefer provider-issued organization/deployment identifiers or independently controlled service records where available. If evidence is unavailable, preserve UNKNOWN rather than inferring separation.

## 5. Review package and procedure

The review package must be reproducible from the immutable manifest and include:

1. The exact original task prompt and its stable turn identity.
2. The ordered outputs and durable references of all eight peer roles.
3. Explicit failed, interrupted, and skipped role states; never omit them silently.
4. The source and evidence references supporting claims of implementation, testing, or verification.
5. The relevant operational constraints, authorization boundaries, unresolved disagreements, and known limitations.
6. The review rubric and contract version.

Baldr must assess, at minimum:
- whether claims are supported by the supplied evidence;
- whether proposed, implemented, tested, verified, and adopted states are distinguished;
- missing roles, evidence, tests, provenance, or dependencies;
- contradictions and unresolved disagreements among the eight roles;
- unsafe replay, authorization, privacy, security, or deployment assumptions;
- what remains unknown and what evidence would resolve it.

The review should identify which findings are independently checked against supplied evidence and which are only critiques of the peers' reasoning. Baldr must not certify its own output. User adoption remains a separate, authenticated action.

A future implementation may use a fresh, isolated reviewer conversation/thread to reduce contamination from prior Baldr output. If it instead sends the complete shared run history, it must state that the reviewer saw the prior conversation context and must not claim a clean-room review.

## 6. Persistence and ledger integration

The protected vault is the source of truth for the complete review package, invocation receipt, and response. The plaintext run ledger may contain only allowlisted metadata:
- invocation ID;
- input and response SHA-256 digests;
- peer outcome references;
- durable Baldr turn reference;
- separation level and typed provenance status;
- timestamps and typed terminal status.

The handler must not mark the Baldr role as successfully reviewed unless:
- all eight peer outcomes are represented;
- every successful peer reference matches exactly one durable turn;
- the review input manifest is durably recorded;
- a separately attributable invocation has completed;
- the returned Baldr response is durably stored and linked to that invocation;
- provenance status and unresolved limitations are recorded.

A configured route with no invocation receipt is not a review. A provider response without durable persistence is an unknown outcome requiring reconciliation. A missing or invalid review record must fail closed and must not be converted into a successful Baldr checkpoint.

## 7. Test requirements before implementation is adopted

Mock-only tests must cover:
- no Baldr call before all eight peer outcomes exist;
- missing or duplicate successful peer turns block review;
- exact input manifest is stable and order-sensitive;
- invocation ID reuse or conflicting receipt reuse is rejected;
- provider request failure, timeout, cancellation, and ambiguous response are typed distinctly;
- response persistence failure prevents a successful review checkpoint;
- a prior Baldr output is not mistaken for a new review invocation;
- missing control-plane evidence remains UNKNOWN and cannot be promoted by configuration labels;
- user adoption is not implied by a successful Baldr review.

A later live integration test, if explicitly authorized, must be separately labeled from mock tests and record the actual provider/account/deployment evidence available. It must not expose credentials or claim model-weight independence without authoritative evidence.

## 8. Adoption boundary

This document is a proposed contract only. It does not implement a separate reviewer service, authenticate provider ownership, create credentials, invoke a model, or authorize host activation. The current route-separated Baldr path remains useful as a final role in the orchestration, but its independence is **UNVERIFIED** until a distinct invocation and its provenance are recorded.

No host activation, provider credentials, live model calls, spending, deployment, merge, or device changes are authorized by this document.
