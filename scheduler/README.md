# Nine Builders Independent Scheduler 0.2

A dependency-free persistent scheduler core and single-process service runner for
the Analysis Engine and, later, the Samsung Conversational Builder.

## Requirements

- Node.js 20 or newer
- No third-party packages
- One host process with persistent local storage

## Start the service

From this directory:

```sh
npm start
```

The runner polls the task store every 15 seconds by default. It uses
`./data/scheduler.json` for task and run history. Configure
`SCHEDULER_STATE_FILE` to select another path and `SCHEDULER_POLL_MS` to set a
poll interval from 1,000 to 300,000 milliseconds. The process handles SIGINT and
SIGTERM and waits for its current tick before exiting.

This is a foreground process, not an installed operating-system service. It only
runs while the host process remains alive. Use an operating-system service manager
or supervised container for unattended operation after deployment hardening.

## Manage tasks

```sh
npm run scheduler -- list
npm run scheduler -- add-daily-heartbeat morning-check "Morning scheduler check" America/New_York 9 0
npm run scheduler -- add-weekly-heartbeat monday-check "Monday scheduler check" America/New_York 9 0 1
npm run scheduler -- pause morning-check
npm run scheduler -- resume morning-check
npm run scheduler -- runs morning-check
```

Weekly days use 0=Sunday through 6=Saturday. The service currently registers only
the safe demonstration handler `system.heartbeat`. Real integrations must be
added as reviewed, explicitly registered handlers.

## Scheduling behavior

- Fixed intervals are anchored to their scheduled timestamp.
- Daily and weekly wall-clock schedules use an IANA timezone such as
  `America/New_York`.
- Daylight-saving transitions are handled by local wall-clock matching.
- A nonexistent spring-forward time is skipped to the next valid scheduled day.
- A repeated fall-back wall time runs only once per local calendar date.
- Work is sequential and bounded to five due tasks per polling tick by default.
- If a process stops while a handler is running, the run is marked interrupted
  after restart. It is not automatically replayed because the external outcome
  may be unknown.

## Security and persistence boundaries

- Task data selects only a registered handler; it cannot select arbitrary shell
  commands or dynamically load code.
- The JSON store uses atomic replacement and restrictive file permissions where
  supported.
- The store is designed for one local process. It is not a distributed database
  or multi-host lock.
- The runner does not expose a network management API.
- No credentials are stored in source control.
- The demonstration heartbeat does not contact external services.

Before unattended use, add a single-instance lock, backup/restore checks, bounded
history retention, authenticated management, notifications, operational metrics,
and independently reviewed handlers with least-privilege credentials and approval
gates. GitHub build monitoring is the next integration, not yet active.
