# Nine Builders Independent Scheduler 0.1

A dependency-free scheduling core intended for the Analysis Engine and Samsung Conversational Builder.

## Implemented

- Persistent JSON task definitions and run history.
- Recurring interval schedules anchored to an ISO timestamp.
- Explicitly registered handler functions; task data cannot choose arbitrary commands or load code.
- Sequential execution with a per-tick limit.
- Pause and resume.
- Success, failure and interrupted run records.
- Atomic state-file replacement and duplicate run identifiers.
- No automatic replay after process interruption when an external outcome may be unknown.

## Test

Requires Node.js 20 or newer. Run `npm test` from this directory.

## Current limits

This is a scheduler core, not yet an always-running service. A host process must wake it and call `runDue()`. Prototype 0.1 supports fixed UTC intervals only; local-time daily/weekly schedules and daylight-saving handling are not implemented. The JSON store is for one local process, not distributed workers. No notification, browser management or Android background service exists yet.

Consequential actions require explicit, least-privilege handlers and approval gates. Before unattended deployment, add process supervision, backups, retention limits, timezone-aware recurrence, authenticated management endpoints and independent recovery tests.
