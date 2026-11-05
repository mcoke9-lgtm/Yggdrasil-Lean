# GitHub Evidence Gateway — Read-Only Integration Design

- Status: PROPOSED
- Scope: PR #35 correction lifecycle evidence resolution
- Decision boundary: no credentials, permissions, deployment, or external writes
- Repository: `mcoke9-lgtm/Yggdrasil-Lean` (public repository)

## Why this boundary

`BuilderEvidenceReference` already parses typed commit and Actions references and verifies them through an injected `EvidenceGateway`. The Android app does not yet provide a live implementation. The next adapter should be read-only and should not accept model-supplied arbitrary URLs.

For this public repository, GitHub's unauthenticated REST API can provide the required read evidence. This avoids introducing a token, changing repository permissions, or forwarding a credential to a destination selected by a Builder. Private repositories remain unsupported until the user explicitly authorizes an authentication design.

## Required adapter contract

Implement `GitHubPublicEvidenceGateway` as an `EvidenceGateway` using an injectable GET-only transport.

The adapter accepts only a validated `owner/repository` pair and builds URLs under the fixed origin `https://api.github.com`. It must not accept a URL, hostname, path prefix, or HTTP method from a Builder or a correction record.

### Commit evidence

1. Fetch `GET /repos/{owner}/{repo}/commits/{full_sha}`.
2. Require a successful HTTP response and parse the returned repository identity and full commit SHA.
3. Confirm branch membership with `GET /repos/{owner}/{repo}/compare/{sha}...{branch}`.
4. URL-encode the branch as one path segment; do not concatenate an untrusted raw branch into a URL.
5. Accept only compare status `ahead` or `identical`, and require the comparison's base SHA to equal the requested commit SHA. A `behind`, `diverged`, missing, or malformed result is unverified.

The comparison checks whether the referenced commit is an ancestor of the branch tip at the time of the request. It does not prove that the commit is still the branch tip.

### GitHub Actions run evidence

1. Fetch `GET /repos/{owner}/{repo}/actions/runs/{run_id}`.
2. Require exact repository identity, run ID, workflow name, full `head_sha`, `status=completed`, and `conclusion=success`.
3. Fetch `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs?per_page=100&page=N`.
4. Require every page to be retrieved. The returned `total_count` must equal the number of distinct jobs collected. Follow pagination links only when they remain on the fixed GitHub API origin and the expected jobs endpoint; alternatively use bounded page numbers and stop only when the reported total is satisfied.
5. Require at least one job and require every job to have `status=completed` and `conclusion=success`. Skipped, cancelled, timed-out, queued, in-progress, failed, missing, or unreturned jobs are not success.

A successful workflow run is evidence only for its exact tested commit SHA and workflow identity. It must not be generalized to another branch head or later commit.

## Transport and failure rules

- GET only; no POST, PUT, PATCH, DELETE, workflow dispatch, rerun, or repository mutation.
- Fixed HTTPS origin: `api.github.com`; redirects disabled.
- Explicit connect/read timeouts and a bounded response size.
- Send GitHub's documented API version and an `Accept: application/vnd.github+json` header.
- Do not attach Authorization headers or read Android provider credentials.
- Treat 401, 403, 404, 429, 5xx, rate limiting, malformed JSON, unexpected content type, truncated pagination, timeouts, and transport exceptions as unavailable/unverified.
- Never retry indefinitely. A bounded retry may be considered only for idempotent GET on transient 5xx/429 and must respect `Retry-After`.
- Do not log request headers, tokens, full response bodies, or user conversation content.
- Preserve the exact run ID, workflow name, repository, tested SHA, and retrieval timestamp in the verification result. The current evidence interface may need a richer result object to retain this provenance.
- No evidence resolver result should be represented as an eternal fact: it records what GitHub returned at a particular time.

## Lifecycle gate integration

The present correction ledger checks typed reference syntax and lifecycle order but does not invoke the evidence gateway. The integration must change that boundary:

- `IMPLEMENTED`: verify the commit reference before accepting the milestone.
- `TESTED`: verify the Actions run reference before accepting the milestone.
- `VERIFIED`: Baldr's independent assessment must cite the successful test evidence and the same correction artifact revision. The app should not equate a successful CI run with independent review.
- If the gateway is missing, unavailable, rate-limited, incomplete, or returns mismatched evidence, retain the attempted claim as rejected/unverified diagnostic history where feasible; do not append it as an accepted lifecycle milestone.
- A later retry must be a new verification attempt with its own timestamp and result, not a mutation of the old result.

The adapter should be injected into the existing ledger/coordinator boundary. It must not perform network requests on the Android main thread. The UI should display the verification state and reason without exposing raw API payloads.

## Acceptance tests

Use a fake GET transport; ordinary JVM tests must not depend on live GitHub or network access.

1. Exact commit repository and SHA accepted.
2. Missing commit rejected.
3. Wrong repository or SHA rejected.
4. Branch compare `ahead` and `identical` accepted only when the base SHA matches.
5. `behind`, `diverged`, missing compare result, or malformed branch rejected.
6. Successful Actions run accepted only for exact repository, ID, workflow, and tested SHA.
7. Failed, cancelled, incomplete, or stale/wrong-SHA run rejected.
8. Any unsuccessful job rejects the run.
9. Empty jobs, truncated pagination, duplicate jobs, or mismatched total count reject the run.
10. 401/403/404/429/5xx, timeout, redirect, malformed JSON, oversized body, and wrong host all fail closed.
11. Assert that the transport is GET-only and never receives Authorization.
12. Assert that Builder-controlled input cannot change the API host or introduce query parameters/path traversal.

## Explicit non-goals

- No authentication or private-repository access.
- No credential collection, permission changes, or GitHub App installation changes.
- No CI reruns, branch writes, PR updates, merges, deployments, or scheduler activation.
- No claim of device acceptance from JVM tests.
- No claim that CI success alone constitutes Baldr's independent verification or user adoption.

## Current evidence checkpoint

- Android JVM workflow run 42108072069: completed successfully on PR #35 head `e23da9933d1774da3017d4e8f6784400e72929f5`.
- Lean build and analysis-engine workflow run 42108072053: completed successfully on the same head.
- These runs establish automated CI results for that exact commit. They do not establish live gateway integration or Galaxy S21/device behavior.
