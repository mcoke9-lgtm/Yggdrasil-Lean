import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AuthorityCoordinator } from "../src/authority-coordinator.js";
import { JsonFileStore, MemoryStore } from "../src/store.js";

const fingerprint = "a".repeat(64);
const clock = () => new Date("2026-11-06T20:00:00.000Z");
const initial = { taskId: "task-001", owner: "source-a", taskDefinitionFingerprint: fingerprint };
const transfer = overrides => ({
  taskId: "task-001", transferId: "transfer-001", expectedEpoch: 1,
  expectedOwner: "source-a", newOwner: "destination-b",
  taskDefinitionFingerprint: fingerprint, ...overrides
});

test("initializes authority once and rejects conflicting reinitialization", async () => {
  const coordinator = new AuthorityCoordinator({ store: new MemoryStore(), clock });
  const record = await coordinator.initialize(initial);
  assert.equal(record.epoch, 1);
  assert.equal(record.owner, "source-a");
  assert.deepEqual(await coordinator.initialize(initial), record);
  await assert.rejects(() => coordinator.initialize({ ...initial, owner: "other" }), /already initialized/);
});

test("compare-and-swap advances one epoch and persists the grant", async () => {
  const store = new MemoryStore();
  const coordinator = new AuthorityCoordinator({ store, clock });
  await coordinator.initialize(initial);
  const result = await coordinator.compareAndSwap(transfer());
  assert.equal(result.duplicate, false);
  assert.equal(result.transfer.status, "AUTHORITY_ADVANCED");
  assert.equal(result.transfer.grantedEpoch, 2);
  assert.equal(result.transfer.confirms, "LOCAL_REFERENCE_GRANT_ONLY");
  assert.deepEqual(await coordinator.get("task-001"), {
    taskId: "task-001", owner: "destination-b", epoch: 2, version: 2,
    taskDefinitionFingerprint: fingerprint,
    initializedAt: "2026-11-06T20:00:00.000Z",
    updatedAt: "2026-11-06T20:00:00.000Z", lastTransferId: "transfer-001"
  });
  assert.equal((await store.load()).authorityTransfers.length, 1);
});

test("same transfer ID and identical request returns the original grant", async () => {
  const coordinator = new AuthorityCoordinator({ store: new MemoryStore(), clock });
  await coordinator.initialize(initial);
  const first = await coordinator.compareAndSwap(transfer());
  const retry = await coordinator.compareAndSwap(transfer());
  assert.equal(retry.duplicate, true);
  assert.deepEqual(retry.transfer, first.transfer);
  assert.equal((await coordinator.get("task-001")).epoch, 2);
});

test("same transfer ID with changed request is rejected", async () => {
  const coordinator = new AuthorityCoordinator({ store: new MemoryStore(), clock });
  await coordinator.initialize(initial);
  await coordinator.compareAndSwap(transfer());
  await assert.rejects(
    () => coordinator.compareAndSwap(transfer({ newOwner: "destination-c" })),
    /different request/
  );
});

test("stale or competing compare-and-swap requests cannot both advance authority", async () => {
  const coordinator = new AuthorityCoordinator({ store: new MemoryStore(), clock });
  await coordinator.initialize(initial);
  const results = await Promise.allSettled([
    coordinator.compareAndSwap(transfer()),
    coordinator.compareAndSwap(transfer({ transferId: "transfer-002", newOwner: "destination-c" }))
  ]);
  assert.equal(results.filter(item => item.status === "fulfilled").length, 1);
  assert.equal(results.filter(item => item.status === "rejected").length, 1);
  assert.equal((await coordinator.get("task-001")).epoch, 2);
});

test("authority and transfer receipt survive coordinator reconstruction", async () => {
  const directory = await mkdtemp(join(tmpdir(), "authority-coordinator-"));
  const path = join(directory, "state.json");
  try {
    const first = new AuthorityCoordinator({ store: new JsonFileStore(path), clock });
    await first.initialize(initial);
    const grant = await first.compareAndSwap(transfer());
    const disk = JSON.parse(await readFile(path, "utf8"));
    assert.equal(disk.version, 3);
    const restarted = new AuthorityCoordinator({ store: new JsonFileStore(path), clock });
    assert.deepEqual(await restarted.get("task-001"), await first.get("task-001"));
    assert.deepEqual(await restarted.getTransfer("transfer-001"), grant.transfer);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects mismatched task fingerprint and malformed epochs", async () => {
  const coordinator = new AuthorityCoordinator({ store: new MemoryStore(), clock });
  await coordinator.initialize(initial);
  await assert.rejects(
    () => coordinator.compareAndSwap(transfer({ taskDefinitionFingerprint: "b".repeat(64) })),
    /fingerprint does not match/
  );
  await assert.rejects(() => coordinator.compareAndSwap(transfer({ expectedEpoch: 0 })), /positive safe integer/);
  assert.equal((await coordinator.get("task-001")).epoch, 1);
});

test("migrates version 2 state while preserving existing handoffs", async () => {
  const handoff = { handoffId: "keep-this", status: "HANDOFF_ACCEPTED" };
  const store = new MemoryStore({ version: 2, tasks: [], runs: [], handoffs: [handoff] });
  const state = await store.load();
  assert.equal(state.version, 3);
  assert.deepEqual(state.handoffs, [handoff]);
  assert.deepEqual(state.authorities, []);
  assert.deepEqual(state.authorityTransfers, []);
});
