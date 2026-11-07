# Nine Builder Disconnect–Reconnect Test 001

**Status:** Test implemented; execution not yet verified  
**Branch:** `nine-builder-disconnect-reconnect-test-001`  
**Base:** `nine-builders-overnight-runtime-001`

## Purpose

Exercise the existing Android Builder runtime's behavior when a client disconnects before receiving an append acknowledgement, then reconnects and reads the journal after the runtime service has been recreated.

This is a narrow continuity test. It reuses the existing runtime and append-only conversation journal rather than creating another coordinator or storage system.

## Scenario

1. Start the Builder runtime service.
2. Create a Messenger client and stop its HandlerThread before dispatching an append.
3. Send a valid conversation turn to the runtime with the disconnected Messenger as the reply destination.
4. Send a read request from a fresh client. The runtime serializes requests on its worker thread, so the read follows the append.
5. Confirm the turn is present with the same turn ID, speaker, evidence status, and body.
6. Destroy and recreate the runtime service.
7. Read the same thread again and confirm the record remains intact.

## Acceptance criteria

- The runtime accepts the append even though the original client cannot receive its response.
- A newly connected client can retrieve the committed turn.
- Recreating the Android service does not erase the journal entry.
- The recovered turn retains its original identity, speaker, evidence status, and content.
- The test does not require a model provider, API credential, network connection, or external service.

## Evidence boundary

The test source has been added to `BuilderRuntimeServiceTest`. **It has not yet been run in CI or on a physical device.** Until a successful test run is inspected, this is implemented test code, not a tested or verified result.

This scenario tests a disconnected client and recovery of one host-owned journal. It does **not** prove:
- nine independently running Builder processes;
- each Builder maintaining a separate local checkpoint;
- independent work while the canonical host is unavailable;
- conflict reconciliation between independently modified histories;
- authenticated multi-device synchronization;
- live model-provider availability or autonomous execution.

The existing architecture still uses a single runtime service and canonical journal. The next distributed-continuity gate must test independently persisted role/node checkpoints, partitioned operation, and explicit reconciliation without overwriting either side's history.

## Integrity checkpoint

- **Count:** one disconnected-client append, one reconnect read, one service recreation, one recovery read.
- **Role mapping:** no claim of nine separate workers; the test exercises the shared runtime boundary.
- **Omissions/duplicates:** one uniquely identified turn is expected exactly once.
- **Evidence status:** test implementation committed; execution pending.
- **Correction:** if the test reveals an acknowledgement or persistence ambiguity, preserve the observed failure and correct the runtime/test without rewriting prior evidence.
- **Persistence:** this document and test are committed only to the isolated branch named above; no merge or deployment is performed.
