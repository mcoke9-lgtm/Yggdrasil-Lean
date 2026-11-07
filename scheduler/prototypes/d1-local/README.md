# SCH-001 — Local D1-compatible witness prototype

**Status:** disposable local-only prototype. No Cloudflare account, remote D1 database, Worker deployment, production credentials or task authority was created.

## What this prototype uses

- Wrangler 4 local development.
- Cloudflare's local D1 implementation running through Miniflare/workerd.
- A local D1 migration with the trigger-guarded append/head-projection schema.
- A deliberately small Worker API with a synthetic bearer token.
- A separate loopback checkpoint process that appends a hash-chained JSONL journal, calls `fsync`, and reloads/verifies the journal on restart.
- Node's built-in test runner and HTTP requests to both local processes.

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
4. Executes nineteen synthetic HTTP/SQL and checkpoint-process cases.
5. Stops the local Worker process.

The `/__test/*` seed/state routes are disabled unless the explicit local-test mode is present. Do not deploy this prototype. The API is not production-authenticated and the test routes exist solely to prepare/read disposable fixtures.

## Verified CI execution

**Verified CI result at the initial prototype stage: 8 of 8 passed.**

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
- D1L-13: a simulated old-head restore is detected against a Worker-process-memory checkpoint.
- D1L-14: a consistent rollback of both the event ledger and head projection is detected against a monotonic checkpoint held by the separate Node test-runner process.
- D1L-15: a separate checkpoint process persists two monotonic anchors to a hash-chained journal, restarts and verifies the journal, then detects coordinated D1 rollback after the D1 Worker itself is restarted.
- D1L-16: byte-level journal corruption causes checkpoint-service startup to fail closed.
- D1L-17: a truncated final JSONL record causes startup to fail closed.
- D1L-18: replacing the journal with a previously valid older copy passes internal replay but disagrees with the runner-retained sequence-2 receipt.
- D1L-19: a privileged actor rewrites the latest checkpoint and recomputes a valid local hash chain; replay succeeds, but the rewritten event hash conflicts with the runner-retained receipt.

D1L-13 is only a controlled demonstration: the checkpoint is held in Worker process memory and the test manually rewinds the mutable head projection. D1L-14 improves the threat model: the Node test runner retains the checkpoint outside the Worker and D1 database, then invokes a test-only privileged restore simulation that rewinds both the event ledger and head projection to a mutually consistent earlier state. The Worker compares that restored state with the runner-held checkpoint and must return `WITNESS_ROLLBACK_OR_LOSS`. This is stronger than comparing the head alone, but remains a same-host test harness, not a durable independently administered witness or an actual D1 Time Travel restore. D1L-12 must be interpreted from the CI log: it demonstrates transaction rollback only if the intentionally failing second statement is reached after the event insert statement has been attempted.

## External-checkpoint rollback test

D1L-14 models the critical coordinated-rollback case: both the append-only event history and mutable head projection are rewound together, so internal agreement cannot reveal the loss. The checkpoint is advanced from sequence 1 to sequence 2 only when the next event links to the prior checkpoint hash; a stale attempt to replace the newer checkpoint is rejected in the harness. The latest checkpoint is retained by the separate Node test-runner process and supplied only after the simulated restore. The test-only restore route temporarily removes the event-delete guard to represent privileged restore capability; it is not part of the production API and is disabled outside explicit local-test mode.

D1L-15 advances the prototype: the checkpoint is written by a separate loopback Node process to an append-only JSONL journal, each append is synchronized with `fsync`, and the journal is replayed and hash-chain-verified after restarting that process. The Wrangler D1 Worker is also stopped and restarted before the coordinated rollback fixture. The checkpoint directory is temporary and removed after the test. D1L-16 and D1L-17 exercise fail-closed startup on malformed/corrupted content. D1L-18 demonstrates that a valid older snapshot can replay successfully and therefore requires comparison with a separately retained receipt. D1L-19 demonstrates the harder boundary: an administrator able to rewrite the complete journal and recompute every hash can produce a locally valid chain; only a receipt retained outside that administrator's control detects the substituted head. In these tests, that receipt is held by the same CI test-runner process, so it is a synthetic external comparison—not an independent security domain.

Limitations: both processes and the checkpoint file share one CI host and administrative trust boundary; the journal is not protected against a privileged host administrator rewriting the entire file; the prototype has no production authentication, external signing key, remote replication, independent account, provider restore, or real D1 Time Travel restore. The test verifies local process-restart persistence and rollback comparison—not production-grade witness independence.

## Latest durable-checkpoint CI execution

- GitHub Actions run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42333913174
- Tested code commit: `10b1c9714f5d3b146c461a90e0223115cbd032f7`
- Result: **15 of 15 passed**, including D1L-15 after restarting both the checkpoint process and local D1 Worker.
- The D1L-15 log line and final result were inspected directly. Scheduler, build and analysis checks also passed on the same commit.

## Latest coordinated-rollback CI execution

- GitHub Actions run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42330863396
- Tested code commit: `81790e92b4df82b0ad68b5b7440a2005ccbb41bf`
- Result: **14 of 14 passed**, including the strengthened D1L-14 monotonic-checkpoint case.
- The D1L-14 log line and final 14/14 result were inspected directly. The run used local Wrangler D1/workerd; it was not a remote D1 restore or an independently durable witness test.

## Latest expanded-suite CI execution

- GitHub Actions run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42330551406
- Tested code commit: `c15daad6c1b25328bfb8b2027c553146fc75efc8`
- Result: 13 of 13 passed.

The verified run includes the strengthened rollback case: its second batch statement is a syntactically valid duplicate-primary-key insert. This forces a runtime constraint failure after the first event insert has been submitted to the batch, rather than relying on a deliberately missing table.

## Important gaps

- This is a local Miniflare/workerd-backed D1 test, not a remote D1 test.
- The test harness sends competing requests concurrently from one runner; it is not a distributed multi-region or high-contention proof.
- No external source relinquishment protocol or per-side-effect fencing is implemented.
- The synthetic token is not an identity system; authorization evidence is fixture data.
- Hash values are fixture strings, not a production event-signing or canonicalization standard.
- No production independent continuity anchor, backup/restore exercise, provider outage, rate-limit, durability contract, key custody, or independently administered privileged-administrator defense is implemented. D1L-16–D1L-19 are local adversarial simulations; they do not protect against compromise of the CI runner that holds the comparison receipt.
- Local state may persist between test runs. Tests use distinct synthetic task IDs; do not place real data in this local database.

## CI

`.github/workflows/sch-001-d1-local.yml` runs these tests on pushes affecting this prototype branch/path. The workflow installs Wrangler and executes the same local-only suite. A successful CI run is evidence for these cases at the recorded commit and dependency resolution only.

## Decision boundary

A passing local suite would justify a later, separately approved remote D1 sandbox evaluation. It would not justify provider adoption, authority transfer, production deployment, or activation of any task.

## Latest journal-adversary test expansion

**Status: implementation committed; CI result pending.** The suite now contains D1L-01 through D1L-19. The four new cases distinguish byte corruption, partial-record truncation, valid older-copy replacement, and privileged full-chain rewriting with recomputed hashes. In particular, D1L-18 and D1L-19 deliberately show that local journal replay can succeed after rollback or privileged rewriting; the runner-held prior receipt is what detects the mismatch. No production-grade independence claim follows from these local tests.
