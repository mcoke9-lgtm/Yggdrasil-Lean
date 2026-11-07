# SCH-001 — Local D1-compatible witness prototype

**Status:** disposable local-only prototype. No Cloudflare account, remote D1 database, Worker deployment, production credentials or task authority was created.

## What this prototype uses

- Wrangler 4 local development.
- Cloudflare's local D1 implementation running through Miniflare/workerd.
- A local D1 migration with the trigger-guarded append/head-projection schema.
- A deliberately small Worker API with a synthetic bearer token.
- Node's built-in test runner and HTTP requests to the local Worker.

The local runtime is intended to exercise the D1 API and D1-compatible SQLite behavior, rather than a separate system SQLite library. It is still not a remote Cloudflare production database.

## Run

From this directory, with Node.js 22 or later:

```sh
npm install
npm test
```

The test runner:
1. Applies the migration to Wrangler's local D1 store only.
2. Starts `wrangler dev --local` bound to 127.0.0.1.
3. Sets a synthetic token and explicit `PROTOTYPE_MODE=local-test`.
4. Executes eight synthetic HTTP/SQL cases.
5. Stops the local Worker process.

The `/__test/*` seed/state routes are disabled unless the explicit local-test mode is present. Do not deploy this prototype. The API is not production-authenticated and the test routes exist solely to prepare/read disposable fixtures.

## Verified CI execution

**Verified CI result: 8 of 8 passed.**

- GitHub Actions run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42330324162
- Tested commit: `6c921f330c16e714135936ca2f07f69e190e1294`
- Runtime: Node.js 22.23.4; Wrangler 4.158.0
- Result: 8 tests passed, 0 failed.
- The run used the local Wrangler D1 binding and local workerd runtime; no remote binding or deployment was configured.

Initial CI iterations exposed and corrected three test-harness issues: an unsupported migration flag, failure to pass the synthetic token as a Worker variable, and accidental discovery of the prototype runner by the parent scheduler's recursive test command. The successful run followed those corrections. The parent scheduler workflow also passed after the runner was moved out of its test-discovery path.

## Test cases

- D1L-01: valid append and head advancement.
- D1L-02: two competing stale writers; exactly one accepted.
- D1L-03: identical event retry returns the original receipt.
- D1L-04: altered payload reusing an event ID is rejected.
- D1L-05: stale expected sequence is rejected without mutation.
- D1L-06: missing authentication is rejected.
- D1L-07: original receipt is queryable by event ID.
- D1L-08: transfer event increments generation and changes owner.
- D1L-09: event UPDATE and DELETE are blocked.
- D1L-10: sequential events preserve hash-chain continuity.
- D1L-11: incorrect previous-event hash is rejected.
- D1L-12: failed multi-statement batch leaves event and head unchanged; the second statement is a valid duplicate-primary-key insert that fails during batch execution.
- D1L-13: a simulated old-head restore is detected against a process-memory checkpoint.

D1L-13 is only a controlled demonstration: the checkpoint is held in Worker process memory and the test manually rewinds the mutable head projection. It does **not** demonstrate durable, independent, cross-account or production restore detection. D1L-12 must be interpreted from the CI log: it demonstrates transaction rollback only if the intentionally failing second statement is reached after the event insert statement has been attempted.

## Latest expanded-suite CI execution

- GitHub Actions run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42330509926
- Tested code commit: `f08a002379e205265289719740929542eaebec6f`
- Result: 13 of 13 passed.

The rollback case was subsequently strengthened so its second batch statement is a syntactically valid duplicate-primary-key insert. This forces a runtime constraint failure after the first event insert has been submitted to the batch, rather than relying on a deliberately missing table.

## Important gaps

- This is a local Miniflare/workerd-backed D1 test, not a remote D1 test.
- The test harness sends competing requests concurrently from one runner; it is not a distributed multi-region or high-contention proof.
- No external source relinquishment protocol or per-side-effect fencing is implemented.
- The synthetic token is not an identity system; authorization evidence is fixture data.
- Hash values are fixture strings, not a production event-signing or canonicalization standard.
- No external independent continuity anchor, backup/restore exercise, provider outage, rate-limit, durability, key custody, or privileged-administrator threat test is included.
- Local state may persist between test runs. Tests use distinct synthetic task IDs; do not place real data in this local database.

## CI

`.github/workflows/sch-001-d1-local.yml` runs these tests on pushes affecting this prototype branch/path. The workflow installs Wrangler and executes the same local-only suite. A successful CI run is evidence for these cases at the recorded commit and dependency resolution only.

## Decision boundary

A passing local suite would justify a later, separately approved remote D1 sandbox evaluation. It would not justify provider adoption, authority transfer, production deployment, or activation of any task.
