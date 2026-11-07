# SCH-001 — Durable handoff inbox prototype

**Status:** development-branch implementation; not adopted or production-ready.

## Scope

SCH-001 adds a durable receiving inbox to the Nine Builders Independent Scheduler. It does not replace the scheduler, add another Builder, activate tasks, or establish distributed execution authority.

## State version 2

The state envelope is:

```json
{
  "version": 2,
  "tasks": [],
  "runs": [],
  "handoffs": []
}
```

A version-1 state file is accepted and normalized to version 2 in memory. Existing task and run arrays are preserved without rewriting their records. The next successful save writes the version-2 envelope. Unsupported versions and malformed collections fail closed.

## Handoff request

`HandoffInbox.receive(request)` requires:

- `handoffId`: stable source-generated identifier
- `sourceScheduler`: stable source scheduler identifier
- `taskDefinition`: complete task definition as immutable handoff data
- `taskDefinitionVersion`: optional source version label
- `authorization`: evidence that the transfer was authorized
- `sourceRejectionEvidence`: evidence of the capacity rejection or other permitted handoff trigger

The destination scheduler identifier is configured on the inbox instance, not trusted from the request.

## Persistence and duplicate behavior

The inbox computes a SHA-256 fingerprint over the canonicalized immutable request. It writes the handoff record and receipt through the store before returning either to the caller.

- First receipt: `HANDOFF_PERSISTED`
- Same ID and same fingerprint: returns the original stored handoff and receipt; no second record
- Same ID and different fingerprint: rejected for reconciliation
- Store write failure: no persistence receipt is returned
- Restart: handoff and receipt are recoverable from the state store

The receipt is evidence of destination persistence only. It is not evidence of acceptance, activation, scheduling, or execution.

## Explicit exclusions

Not implemented in this increment:

- Source scheduler capacity-rejection detection or automatic handoff
- Remote transport, authentication protocol, or destination discovery
- Destination task validation against a live handler registry
- Task activation or schedule registration
- Single execution authority, leases, fencing tokens, or distributed failover
- Cancellation and status reconciliation across two live schedulers
- Dashboard or CLI management of handoffs

Until these exist and are tested, this module is a local durable inbox primitive, not a distributed scheduler or a complete capacity handoff.

## Acceptance tests added

1. Version-1 migration preserves task and run records.
2. Receipt is returned only after persistence.
3. Identical duplicate returns the original receipt without duplication.
4. Changed request under an existing ID is rejected.
5. Restart recovers the original handoff and receipt.
6. Missing authorization/evidence and self-handoff are rejected.

The tests are specified in `test/handoff-inbox.test.js`; they must run in CI or a local Node.js 20+ environment before the increment can be marked tested.
