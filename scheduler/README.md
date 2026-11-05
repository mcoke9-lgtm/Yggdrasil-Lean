# Nine Builders Independent Scheduler 0.3

A dependency-free, single-process scheduling service with a local browser dashboard.
It is intended to become shared infrastructure for the Analysis Engine and the
Samsung Conversational Builder.

## Requirements

- Node.js 20 or newer
- No third-party packages
- A local computer that can keep a Node.js process running

## Start the service

From this directory:

```sh
npm start
```

By default, the service:
- Checks for due work every 15 seconds.
- Stores task definitions and run history in `./data/scheduler.json`.
- Opens its dashboard at `http://127.0.0.1:4174`.
- Binds only to loopback. It does not listen on the local network or internet.
- Takes a single-instance lock beside the state file.

Configuration:
- `SCHEDULER_STATE_FILE`: persistent state-file path.
- `SCHEDULER_POLL_MS`: 1,000–300,000 milliseconds.
- `SCHEDULER_DASHBOARD_PORT`: dashboard port, default 4174.
- `SCHEDULER_DASHBOARD_HOST`: must remain `127.0.0.1`, `::1`, or `localhost`.
- `SCHEDULER_DASHBOARD_TOKEN`: optional bearer token protecting management APIs.
- `GITHUB_TOKEN` or `GH_TOKEN`: optional GitHub token with Actions read access.

If a dashboard token is configured, open the dashboard first and enter the token
in its access field. The page and static assets are served without the token so
the user can enter it; task and history APIs require it. The token is held in the
page's memory and is not saved to browser storage.

## Dashboard

The dashboard provides:
- Current scheduled tasks and their next execution times.
- Create a daily or weekly GitHub Actions monitor.
- Pause and resume tasks.
- Delete tasks while retaining their execution history.
- View recent execution records and errors.
- Optional browser notifications for new failed scheduler executions.

Browser notifications require explicit permission and work only while the
dashboard page remains open. The scheduler itself continues running when the
dashboard is closed, as long as the host process remains alive.

A new GitHub monitor records a baseline on its first successful check. Existing
workflow runs are not reported as new failures. Later newly completed failures
are stored as failed scheduler executions and appear in the dashboard. The
monitor reads only the exact repository, branch and workflow names configured
in the task. It does not edit code, rerun workflows, merge pull requests, or
change repository settings. It scans up to 1,000 recent runs per check and fails
visibly without advancing its cursor if more history is present.

## Command-line management

The dashboard is the preferred interface while the service is running. The
offline CLI acquires the same process lock, so it refuses to run while the
service is active; stop the service before using it.

```sh
npm run scheduler -- list
npm run scheduler -- add-daily-heartbeat morning-check "Morning check" America/New_York 9 0
npm run scheduler -- add-weekly-heartbeat monday-check "Monday check" America/New_York 9 0 1
npm run scheduler -- add-github-watch build-watch "Watch build failures" mcoke9-lgtm/Yggdrasil-Lean analysis-engine-prototype-001 America/New_York 9 0 "Analysis Engine" "Lean build and analysis engine checks"
npm run scheduler -- pause morning-check
npm run scheduler -- resume morning-check
npm run scheduler -- runs morning-check
```

Weekly days use 0=Sunday through 6=Saturday.

## Service lifecycle and recovery

The runner is a foreground Node.js process, not yet an installed operating-system
service. It handles SIGINT and SIGTERM, waits for the active tick, closes the
dashboard, and releases its process lock. A process lock prevents two scheduler
runners from writing the same state concurrently. If a process is killed
uncleanly and leaves a stale lock, the next start checks whether the recorded
process ID still exists before removing it; malformed locks fail closed.

If the process stops while a handler is running, the run is marked interrupted
after restart. It is not automatically replayed because the external outcome
may be unknown. Task state uses atomic file replacement. The store and lock are
designed for one local process and one host, not distributed workers.

## Safety boundaries

- Only explicitly registered handler functions can execute.
- Task data cannot select arbitrary shell commands or dynamically load code.
- The dashboard binds only to loopback and checks Host and Origin headers.
- API request bodies are size-limited; responses include restrictive browser
  security headers.
- Optional dashboard bearer-token protection is available.
- GitHub credentials are read from environment variables and never saved in
  task data or source control.
- The service does not expose a public or LAN management endpoint.

Before unattended production use, add encrypted/managed secret storage, backup
and restore procedures, bounded run-history retention, external health checks,
and platform-specific service installation. The current service has no remote
push notification channel and is not yet installed on a user's computer or
Android device.

## Nine Builders bounded-run ledger (prototype)

The `src/run-ledger.js` module adds a durable metadata contract for a Nine Builders
work window. It records all nine role identities, an eight-hour maximum deadline,
per-role attempts and outcomes, bounded checkpoint metadata, interruption state,
and explicit safe-to-resume declarations. Baldr is gated until the other eight
roles have completed or been explicitly skipped, and a Baldr review outcome
cannot be silently skipped.

The ledger stores checkpoint metadata (maximum 16 KiB per checkpoint), not model
prompts, credentials, or full conversation payloads. It uses the scheduler's
existing store interface and writes a `builderRuns` collection alongside task
and scheduler-run records.

**Important limitation:** this is a tested state/ledger foundation, not yet a
scheduled Builder executor. It does not invoke model routes, start or supervise
a persistent host, send heartbeats, cancel provider requests, or prove that an
interrupted external operation is safe to replay. A checkpoint is resumable only
when the worker explicitly records `safeToResume: true`; otherwise recovery
must stop for reconciliation. The current scheduler runner does not yet register
a `nine-builders.run` handler.

### Scheduler-to-Builder handler adapter

`src/builder-run-handler.js` registers the `nine-builders.run` handler when
the host supplies both a `BuilderRunLedger` and an explicit executor function.
It invokes the nine roles in the canonical sequence, records per-role checkpoints
and outcomes, and passes the first eight outcomes to Baldr for the final review
pass. A failed role is recorded and does not erase the remaining roles' chance
to contribute.

The adapter deliberately requires the host to inject the executor. It does not
invent provider routes, credentials, consent, or model identity. The current
foreground scheduler runner does not register this handler because no unattended
model-execution host has yet been configured. Automated tests use a deterministic
fake executor; that verifies orchestration and ledger behavior, not live model
behavior or an overnight host.

## Tests

```sh
npm test
```

Tests cover schedule recurrence and daylight-saving transitions, GitHub failure
detection, persistent state, task controls, concurrency during handler execution,
dashboard access controls, process locking, and service startup/shutdown.
