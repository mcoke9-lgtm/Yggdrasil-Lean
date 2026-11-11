# THO-015 — Probe 1 Runtime Evidence

- **Probe:** uncertain-commit retry after process termination immediately following successful snapshot replacement
- **Classification:** REPRODUCED
- **Status:** Runtime-tested; source unchanged
- **Workflow run:** 43014961759
- **Job:** 130503608758 (`probe1`)
- **Workflow commit:** `30b07a19e65f2815c13dbe580994950c29549115`
- **Base YIM-001 source commit:** `6f0ab398f63bcc19db59ba371101da5f6144de3c`
- **Runtime:** GitHub-hosted Ubuntu 26.04.1, CPython 3.13.18
- **Run started:** 2026-11-11T11:48:19Z
- **Run conclusion:** success

## Method

The test created an isolated temporary store and content file. A child Python process replaced `yim001.storage.os.replace` with a wrapper that calls the real `os.replace` and then terminates the process immediately using `os._exit(73)`. The test then inspected the persisted snapshot and retried the same CLI `create` operation once using the ordinary CLI path.

No YIM-001 source file was changed. The harness and workflow are isolated under the `tho-015/probe1-ci-bootstrap` branch.

## Observed results

| Observation | Result |
|---|---|
| Child exit code | 73 |
| Child stdout/stderr | Empty |
| Records after crash | 1 |
| Versions after crash | 1 |
| Retry exit code | 0 |
| Records after retry | 2 |
| Versions after retry | 2 |
| Payloads identical | true |
| Record IDs distinct | true |
| Version IDs distinct | true |
| Workflow job | success |

### Persisted identifiers

First committed record:
- Record ID: `6924e96b-983a-49fb-8052-f79803112375`
- Version ID: `4cb175c2-f141-4bdd-b6a0-75c8d10fefe0`

Retry-created record:
- Record ID: `4829f216-df90-4a5d-9aff-88fe007ca801`
- Version ID: `4adb8b33-594d-44a0-ace2-6dd44ac76fa6`

### Snapshot SHA-256

- After crash: `c40004be5b2ebb35e9f7b6de9f9299bf6d170192c2a28c8670d8bde6234f580c`
- After retry: `d966a58bcd8668d39f6ee390bcfecf33a2f15e54795284518ab77b7d43f8ce1b`

## Conclusion

**REPRODUCED:** the first create operation successfully replaced the snapshot, then the child process terminated before returning success. Retrying the same create request created a second record and second version with identical content but new identities.

This demonstrates that the current CLI `create` path is not idempotent across this uncertain-acknowledgment failure scenario. It does not establish that all storage failures behave this way, nor does it test a future idempotency-key design.

## Boundaries

- Evidence is specific to the inspected source commit and tested GitHub-hosted Ubuntu/Python environment.
- The test uses a process termination immediately after the real replacement returns; it is not a power-loss or filesystem-corruption test.
- No production data was used.
- No YIM-001 source or existing test files were modified.
- No fix is proposed or applied by this evidence record.
- Probe 2 has not started.
