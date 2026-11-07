import test from "node:test";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { JsonFileStore, MemoryStore } from "../src/store.js";
import { HandoffInbox } from "../src/handoff-inbox.js";
import { Scheduler } from "../src/scheduler.js";

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
    const legacyBytes = await readFile(path);
    const expectedSha256 = createHash("sha256").update(legacyBytes).digest("hex");
    await assert.rejects(() => store.load(), /sidecar mismatch/);
    await assert.rejects(() => store.adoptLegacyState({ expectedSha256: "0".repeat(64) }), /does not match/);
    await store.adoptLegacyState({ expectedSha256 });
    const migrated = await store.load();
    assert.equal(migrated.version, 3);
    assert.deepEqual(migrated.tasks, [task]);
    assert.deepEqual(migrated.runs, [run]);
    assert.deepEqual(migrated.handoffs, []);
    await store.save(migrated);
    const disk = JSON.parse(await readFile(path, "utf8"));
    assert.equal(disk.version, 3);
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

test("destination validation blocks unregistered handlers without activation", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  const scheduler = new Scheduler({ store, clock });
  const blocked = await inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition));
  assert.equal(blocked.status, "HANDOFF_BLOCKED");
  assert.match(blocked.validation.reason, /not registered/);
  assert.equal((await store.load()).tasks.length, 0);
  await assert.rejects(
    () => inbox.accept("handoff-001", { acceptanceAuthorization: { granted: true, authority: "reviewer" } }),
    /Only a validated handoff can be accepted/
  );
});

test("valid destination task requires explicit acceptance and remains inactive", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  const scheduler = new Scheduler({ store, clock });
  scheduler.registerHandler("review.run", async () => {});
  const validated = await inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition));
  assert.equal(validated.status, "HANDOFF_VALIDATED");
  assert.equal(validated.validatedTask.handlerType, "review.run");
  await assert.rejects(() => inbox.accept("handoff-001", {}), /Explicit acceptance authorization/);
  const accepted = await inbox.accept("handoff-001", {
    acceptanceAuthorization: { granted: true, authority: "named-reviewer", decisionId: "decision-001" }
  });
  assert.equal(accepted.status, "HANDOFF_ACCEPTED");
  assert.equal(accepted.acceptance.confirms, "HANDOFF_ACCEPTED");
  assert.equal((await store.load()).tasks.length, 0);
});

test("revalidation after acceptance is refused", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  const scheduler = new Scheduler({ store, clock });
  scheduler.registerHandler("review.run", async () => {});
  await inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition));
  await inbox.accept("handoff-001", {
    acceptanceAuthorization: { granted: true, authority: "named-reviewer" }
  });
  await assert.rejects(
    () => inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition)),
    /cannot be revalidated/
  );
});


test("scheduler and inbox mutations sharing one store do not overwrite each other", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  const scheduler = new Scheduler({ store, clock });
  scheduler.registerHandler("review.run", async () => {});
  const task = {
    id: "local-task",
    name: "Local task",
    handlerType: "review.run",
    payload: {},
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-07T09:00:00.000Z" }
  };
  await Promise.all([
    inbox.receive(request()),
    scheduler.addTask(task)
  ]);
  const state = await store.load();
  assert.equal(state.handoffs.length, 1);
  assert.equal(state.tasks.length, 1);
  assert.equal(state.tasks[0].id, "local-task");
});


test("acceptance retry after a lost response returns the original durable acceptance", async () => {
  const store = new MemoryStore();
  const originalSave = store.save.bind(store);
  let loseAcceptanceResponse = true;
  store.save = async state => {
    await originalSave(state);
    if (loseAcceptanceResponse && state.handoffs.some(item => item.status === "HANDOFF_ACCEPTED")) {
      loseAcceptanceResponse = false;
      throw new Error("simulated response loss after durable save");
    }
  };

  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  const scheduler = new Scheduler({ store, clock });
  scheduler.registerHandler("review.run", async () => {});
  await inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition));

  const authorization = {
    granted: true,
    authority: "named-reviewer",
    decisionId: "decision-retry-001"
  };
  await assert.rejects(
    () => inbox.accept("handoff-001", { acceptanceAuthorization: authorization }),
    /simulated response loss/
  );

  const restartedInbox = new HandoffInbox({
    store, clock, destinationScheduler: "independent-scheduler"
  });
  const recovered = await restartedInbox.accept("handoff-001", {
    acceptanceAuthorization: authorization
  });
  assert.equal(recovered.status, "HANDOFF_ACCEPTED");
  assert.equal(recovered.acceptance.authorizationFingerprint.length, 64);
  assert.equal(recovered.acceptance.acceptedAt, clock().toISOString());
  assert.equal((await store.load()).handoffs.length, 1);
  assert.equal((await store.load()).tasks.length, 0);
});

test("accepted handoff retry rejects changed authorization", async () => {
  const store = new MemoryStore();
  const inbox = new HandoffInbox({ store, clock, destinationScheduler: "independent-scheduler" });
  await inbox.receive(request());
  const scheduler = new Scheduler({ store, clock });
  scheduler.registerHandler("review.run", async () => {});
  await inbox.validate("handoff-001", definition => scheduler.validateHandoffTask(definition));

  await inbox.accept("handoff-001", {
    acceptanceAuthorization: {
      granted: true,
      authority: "named-reviewer",
      decisionId: "decision-original"
    }
  });
  await assert.rejects(
    () => inbox.accept("handoff-001", {
      acceptanceAuthorization: {
        granted: true,
        authority: "different-reviewer",
        decisionId: "decision-changed"
      }
    }),
    /different or unverifiable authorization/
  );
  assert.equal((await store.load()).handoffs[0].acceptance.authorization.authority, "named-reviewer");
});
