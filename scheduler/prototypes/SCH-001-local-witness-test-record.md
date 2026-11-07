# SCH-001 local SQLite witness prototype — execution record

**Status:** local SQLite analogue tested; D1 compatibility NOT tested; prototype NOT adopted.

## Environment and scope

- Executed with Python standard-library `sqlite3` against an in-memory SQLite database.
- Disposable synthetic task only: `task-1`, fingerprint `fp-1`, initial sequence/generation `0/0`, owner `source`.
- Schema source: `scheduler/prototypes/SCH-001-local-witness-schema.sql`.
- No external service, cloud account, credentials, Android device, production task or side effect used.
- The SQL trigger validates expected sequence, generation, owner, task fingerprint, event hash linkage and transition shape before insert; an AFTER INSERT trigger advances the head.

## Test results

<badge>Local run: 16 passed, 0 failed</badge>

| Test | Case | Result |
|---|---|---|
| LSQL-01 | Valid review event advances sequence/head | PASS |
| LSQL-02 | Two submissions from same prior head: first accepted, stale competitor rejected | PASS |
| LSQL-03 | Stale sequence rejected without mutation | PASS |
| LSQL-04 | Stale generation rejected without mutation | PASS |
| LSQL-05 | Wrong current owner rejected | PASS |
| LSQL-06 | Wrong task fingerprint rejected | PASS |
| LSQL-07 | Transfer increments generation and changes owner | PASS |
| LSQL-08 | Duplicate event ID cannot create a second row | PASS |
| LSQL-09 | Reused event ID with altered payload cannot create a second row | PASS |
| LSQL-10 | Wrong previous-event hash rejected | PASS |
| LSQL-11 | Event UPDATE and DELETE blocked by prototype triggers | PASS |
| LSQL-12 | Trigger rejection leaves event table and head unchanged | PASS |
| LSQL-13 | Later failing statement in an explicit transaction rolls back earlier insert/head update | PASS |
| LSQL-14 | Original receipt can be queried by the original event ID after simulated lost response | PASS |
| LSQL-15 | A separately retained sequence/hash checkpoint detects a simulated older restored database | PASS |
| LSQL-16 | Second event links to the first event hash | PASS |

The local execution produced 16 tests run, 0 failures and 0 errors.

## Important interpretation limits

1. **This is SQLite evidence, not D1 evidence.** It does not establish Cloudflare D1's trigger support, trigger semantics, transaction guarantees, concurrent request serialization, or durability.
2. LSQL-02 models two stale submissions sequentially against one SQLite database. It is not a simultaneous multi-client or multi-region concurrency test.
3. LSQL-08/09 establish uniqueness rejection only. They do not implement the Worker idempotency resolver that compares canonical immutable payloads and returns the original receipt for an identical retry. That API behavior remains unimplemented.
4. LSQL-14 queries the original receipt after a simulated lost response; it does not simulate a network failure or prove exactly-once external effects.
5. LSQL-15 is a simplified older-snapshot comparison. It does not exercise a provider restore operation, signed external checkpoint, key custody or independent administrative domain.
6. UPDATE/DELETE triggers are not absolute immutability against privileged schema owners or database administrators.
7. Authentication, authorization, source quiescence/relinquishment, destination fencing at side-effect boundaries, rate-limit behavior, Android lifecycle and service unavailability are outside this local SQL test.
8. Event hashes in the test harness are deterministic synthetic digests, not a production canonicalization/signature protocol.

## Decision

**Supported conclusion:** the proposed trigger-guarded append/head-projection pattern behaves as intended in this local SQLite experiment for the tested cases.

**Not supported:** claiming that Cloudflare D1 is suitable or that SCH-001 authority transfer is safe, implemented, or active.

Next gate: run the same focused cases in a D1-compatible local runtime, then verify against a Cloudflare D1 sandbox only after explicit approval. Keep all task activation and authority transfer disabled.
