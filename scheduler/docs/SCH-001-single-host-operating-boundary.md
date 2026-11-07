# SCH-001 — Single-host operating boundary and recovery

**Status:** development hardening and test specification. This is not production certification or authorization to activate transferred tasks.

## Operating boundary

The supported boundary is one scheduler runner process on one host using one local state path. The runner acquires an exclusive filesystem lock before constructing the scheduler, registering handlers, or opening the dashboard. A second runner using the same lock path must fail closed.

The process lock is a local exclusion mechanism only. It is not a distributed lease, quorum, authority epoch, or fencing credential. Do not share the JSON state file over an unverified network filesystem or use the lock to coordinate multiple hosts.

## Lock behavior

The lock is created with exclusive file creation and restrictive permissions. It records the owning process ID, a random ownership token, and creation time. The owner syncs the lock file before reporting successful acquisition. Release removes the lock only when the on-disk PID and token still match the current owner.

**Stale lock policy: manual inspection; no automatic reclamation.** A PID can be reused, and a check-then-unlink stale-lock recovery path can race with another process creating a new lock. Therefore, an existing lock—including one whose PID appears absent—is never automatically removed. If the lock is malformed or cannot be read, startup fails closed. An operator must independently verify that the scheduler is stopped before manually removing a stale lock. Preserve the lock file as incident evidence until that check is complete.

This intentionally trades automatic restart convenience for a clearer single-host exclusion boundary. A future OS-native lock primitive may improve crash cleanup, but it must be tested on the target operating system and filesystem before replacing this policy.

## Startup and shutdown

1. Acquire the process lock before any scheduler work.
2. If acquisition fails, do not start the dashboard, run handlers, or modify scheduler state.
3. Register shutdown handlers before publishing readiness.
4. On SIGINT/SIGTERM, stop new ticks, wait for the active tick, close the dashboard, then release the lock.
5. If shutdown is unclean and the lock remains, require operator inspection before removal and restart.
6. Do not interpret lock absence as proof that an interrupted external handler had no effect. Existing run recovery continues to mark in-flight work interrupted and does not automatically replay it.

## State and backup recovery

- Missing state may initialize an empty scheduler only when this is a genuinely new installation, not when an expected state file has disappeared.
- Invalid JSON, unsupported state versions, permission errors, disk errors, and unexpected replacement must stop startup; do not overwrite or silently reset the file.
- A backup restore is an authority-history event. Quarantine the scheduler and do not activate transferred tasks or advance authority based only on the restored JSON.
- Preserve the original state file, backup, lock, and relevant logs before recovery.
- Compare task/run/handoff/authority history against an independently retained checkpoint or reviewed evidence. If the authority epoch may have rolled back, local authority is conflicted and cannot authorize execution.
- Never choose an owner by timestamp, reachability, or whichever scheduler starts first.
- Do not automatically replay interrupted handlers. Reconcile the external effect first; operation classes requiring human review remain stopped.

## Required verification before describing this mode as tested

1. Two independent OS processes contend for the same lock path; only one acquires it.
2. Clean shutdown releases only the lock created by that process.
3. A malformed or partial lock is preserved and blocks startup.
4. A lock with a changed ownership token is not deleted by the former owner.
5. Process crash leaves a lock that blocks restart until explicit operator inspection.
6. Invalid, missing-when-expected, or restored state follows the documented fail-closed/quarantine policy.
7. Disk-full, interrupted write, abrupt power loss, and target-filesystem behavior are tested on the actual deployment platform.

The repository process-lock tests exercise the first four behaviors with synthetic local conditions. They do not simulate real power loss, prove fsync/rename durability for the scheduler state file, validate every filesystem, or establish external fencing.

## Explicit non-guarantees

This mode does not provide automatic stale-lock recovery, cross-process authority CAS for arbitrary callers, multi-host coordination, backup rollback resistance, distributed failover, effect-boundary fencing, or guaranteed exactly-once external actions. The current authority coordinator remains LOCAL_REFERENCE_GRANT_ONLY; it must not authorize task activation.

## Operator recovery checklist

- Confirm the host and process identity from the operating system, not only from the lock file.
- Confirm no scheduler process is running against the same state path.
- Copy the state file, lock file, backup and logs before changing anything.
- If the lock is stale, remove it only after independent confirmation and record who performed the action and why.
- If state may be missing, corrupt, replaced or rolled back, quarantine rather than initialize or resume automatically.
- Restart only after the state and any in-flight external effects have been reconciled.
