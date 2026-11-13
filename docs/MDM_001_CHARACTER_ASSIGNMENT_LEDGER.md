# MDM-001 — Character Assignment Ledger (Milestone 1)

## Purpose

Introduce durable, bounded assignment records for the Nine Characters. This is the persistence foundation for later dispatch, recovery, and return-report integration.

## Existing systems retained

- Android WorkManager remains the existing scheduled-work host.
- `SchedulerDb` remains the existing GitHub schedule/checkpoint store.
- `BuilderRuntimeService` remains the canonical local IPC host and conversation-journal owner.
- `BuilderConversationCoordinator` remains the current in-process role coordinator.
- `CharacterAssignmentStore` is a separate durable ledger; it does not replace any of these systems.

## Assignment contract

Each record contains a stable assignment ID, Character identity, requester, objective, scope, permission and constraint documents, latest checkpoint/report snapshots, lifecycle status, and timestamps.

The authoritative payload history is a separate append-only `assignment_payloads` table. Every checkpoint and return report is inserted as a new typed, timestamped record; previous versions are retained and can be read in sequence. The assignment row's checkpoint/report columns are convenience snapshots only, not the history of record.

The append-only `assignment_events` table now distinguishes `LIFECYCLE`, `CHECKPOINT`, and `RETURN_REPORT` event types. SQLite triggers reject updates and deletes to both event and payload history. State transitions remain validated by the store.

Database version 2 migrates any pre-existing latest checkpoint/report values from version 1 into the payload history before normal operation continues.

The nine Character identities are validated against the existing role enum. Existing role descriptions and routing semantics still require reconciliation with the current adopted Nine Characters protocol; this milestone deliberately does not rewrite them.

## Lifecycle

`CREATED → AUTHORIZED → QUEUED → ACCEPTED → RUNNING`

Work may then be checkpointed, blocked, interrupted, returned, verified, or closed according to the transition rules in the implementation.

A stored `RETURNED` report is not automatically `VERIFIED`. A checkpoint is a recovery record, not proof that work is executing.

## Explicit limits of Milestone 1

This milestone adds **persistence only**. It does not yet:

- dispatch a Character or start a background worker;
- connect WorkManager schedules to assignment records;
- resume an interrupted assignment automatically;
- call a model/provider;
- authenticate remote senders or synchronize devices;
- grant permissions or authorize consequential actions;
- append reports to the canonical conversation journal or Living Registry;
- establish independent Character execution.

## Next integration milestone

Add a governed dispatcher that claims authorized queued assignments, checks capacity and permissions, executes bounded work through an existing provider adapter, checkpoints progress, and returns a typed report to the canonical runtime. Integrate with WorkManager without creating a competing scheduler. Keep dispatch and execution disabled until the authorization gate and role mapping are reconciled.

## Verification requirements

- JVM build and unit tests pass.
- Robolectric/SQLite tests cover create/read, invalid Character, legal and illegal transitions, multiple checkpoint/report versions, ordered payload history, rejection of payload-history updates/deletes, and process/store reopen.
- No migration or destructive change to existing scheduler/runtime databases.
- Existing PR #48–#51 experiments remain untouched and independently reviewable.

## WorkManager dispatch increment 1

The follow-on branch adds an explicit `queueForDispatch` operation. It may only queue an assignment whose current lifecycle permits the transition from `AUTHORIZED` to `QUEUED`. The operation appends a unique WorkManager request using a serialized unique-work chain.

Each dispatcher invocation claims at most one oldest queued assignment in a database transaction and records `QUEUED → ACCEPTED`. The current worker deliberately does not invoke a model or provider: because no approved execution adapter is connected to this worker yet, it writes a structured `NO_EXECUTION_ADAPTER` return report and transitions the assignment to `BLOCKED`. It never marks work `RUNNING` or implies that a Character performed the objective.

This is a tested queue/claim integration target, not independent Character execution. Provider routing, capacity checks, checkpoint/resume during execution, canonical journal return, and human authorization UX remain future increments.
