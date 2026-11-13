# Repository Coordination Registry — Read-only Prototype

Status: isolated prototype; not integrated into the Builder runtime or scheduler.

## Purpose

Prove that multiple chat sessions can independently register repository-reading tasks against the same immutable commit, record separate checkpoints, and discover one another's activity without silently overwriting registry state.

## Run

Requires Node.js 20 or newer.

```sh
cd repository-coordination
npm test
```

## Current operations

- `registerSession`: record a chat session and its optional Builder roles.
- `requestRead`: register a read-only task pinned to a full Git commit SHA.
- `recordCheckpoint`: append a task checkpoint using optimistic revision checking.
- `listActiveSessions`: inspect active sessions and their tasks.
- `exportAuditLog`: retrieve a detached, ordered event-log snapshot.

The in-memory registry serializes mutations within one process. Concurrent checkpoint requests with the same expected revision result in one accepted update and one explicit revision conflict. A session cannot checkpoint another session's task.

## Safety boundary

This prototype does not clone, read, write, or modify Git repositories. It does not create branches, grant write leases, merge changes, authenticate chat identities, or persist state across process restarts. Session IDs are caller-supplied labels, not authenticated identities.

It is therefore a concurrency-control logic fixture, not a multi-host coordination service. Production use requires authenticated session identity, durable transactional storage with compare-and-swap semantics, lease/fencing support for write tasks, backup/recovery, and an explicit authorization layer.

## Acceptance status

- Parallel registration: covered by automated test.
- Same-commit concurrent read-task registration: covered by automated test.
- Competing checkpoint updates: covered by optimistic revision-conflict test.
- Cross-session task ownership: covered by automated test.
- Audit snapshot isolation: covered by automated test.
- Actual simultaneous ChatGPT sessions: not yet exercised.
- Durable or distributed operation: not implemented.
