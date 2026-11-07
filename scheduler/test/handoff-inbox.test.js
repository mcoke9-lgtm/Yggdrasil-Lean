import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { JsonFileStore, MemoryStore } from "../src/store.js";
import { HandoffInbox } from "../src/handoff-inbox.js";

const clock = () => new Date("2026-11-06T12:00:00.000Z");
const request = overrides => ({
  handoffId: "handoff-001",
  sourceScheduler: "chatgpt-host",
  taskDefinition: {
    id: "daily-review",
    name: "Daily review",
    handlerType: "review.run",
    payload: { scope: "repository" },
    schedule: { kind: "interval", intervalMs: 86400000, startAt: "2026-11-07T09:00:00.000Z" }
  },
  taskDefinitionVersion: "1",
  authorization: { granted: true, authority: "user-request-123" },
  sourceRejectionEvidence: { code: "CAPACITY_REACHED", observedAt: "2026-11-06T11:59:00.000Z" },
  ...overrides
});

test("migrates version 1 state without changing task or run records", async () => {
  const task = { id: "legacy-task", payload: { keep: true } };
  const run = { id: "legacy-task:occurrence", status: "succeeded" };
  const directory = await mkdtemp(join(tmpdir(), "scheduler-v1-migration-"));
  const path = join(directory, "state.json");
  try {
    await import("node:fs/promises").then(({ writeFile }) =>
      writeFile(path, JSON.stringify({ version: 1, tasks: [task], runs: [run] })));
    const store = new JsonFileStore(path);
    const migrated = await store.load();
    assert.equal(migrated.version, 2);
    assert.deepEqual(migrated.tasks, [task]);
    assert.deepEqual(migrated.runs, [run]);
    assert.deepEqual(migrated.handoffs, []);
    await store.save(migrated);
    const disk = JSON.parse(await readFile(path, "utf8"));
    assert.equal(disk.version, 2);
    assert.deepEqual(disk.tasks, [task]);
    assert.deepEqual(disk.runs, [run]);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("persists a handoff before returning a persistence receipt", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  const result = await inbox.receive(request());
  assert.equal(result.duplicate, false);
  assert.equal(result.handoff.status, "HANDOFF_PERSISTED");
  assert.equal(result.receipt.confirms, "HANDOFF_PERSISTED");
  assert.equal(result.receipt.destinationScheduler, "independent-scheduler");
  assert.equal(result.receipt.requestFingerprint, result.handoff.requestFingerprint);
  assert.equal((await store.load()).handoffs.length, 1);
  assert.equal(result.handoff.status === "TASK_ACTIVATED", false);
});

test("same handoff ID and same request returns the original receipt without duplication", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  const first = await inbox.receive(request());
  const retry = await inbox.receive(request());
  assert.equal(retry.duplicate, true);
  assert.equal(retry.receipt.receiptId, first.receipt.receiptId);
  assert.equal((await store.load()).handoffs.length, 1);
});

test("same handoff ID with changed request is rejected", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  await assert.rejects(
    () => inbox.receive(request({ taskDefinitionVersion: "2" })),
    /different request fingerprint/
  );
  assert.equal((await store.load()).handoffs.length, 1);
});

test("handoff survives inbox and store reconstruction", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-handoff-restart-"));
  const path = join(directory, "state.json");
  try {
    const first = new HandoffInbox({
      store: new JsonFileStore(path), clock, destinationScheduler: "independent-scheduler"
    });
    const saved = await first.receive(request());
    const restarted = new HandoffInbox({
      store: new JsonFileStore(path), clock, destinationScheduler: "independent-scheduler"
    });
    const recovered = await restarted.get("handoff-001");
    assert.equal(recovered.requestFingerprint, saved.handoff.requestFingerprint);
    assert.equal(recovered.receipt.receiptId, saved.receipt.receiptId);
    assert.equal((await restarted.list()).length, 1);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects missing authorization, missing rejection evidence and self-handoff", async () => {
  const inbox = new HandoffInbox({
    store: new MemoryStore(), clock, destinationScheduler: "independent-scheduler"
  });
  await assert.rejects(() => inbox.receive(request({ authorization: null })), /Authorization evidence/);
  await assert.rejects(() => inbox.receive(request({ sourceRejectionEvidence: null })), /rejection evidence/);
  await assert.rejects(
    () => inbox.receive(request({ sourceScheduler: "independent-scheduler" })),
    /cannot hand off.*itself/
  );
});
