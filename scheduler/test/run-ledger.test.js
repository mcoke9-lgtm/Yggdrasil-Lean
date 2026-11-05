import test from "node:test";
import assert from "node:assert/strict";
import { MemoryStore } from "../src/store.js";
import {
  BuilderRunLedger, BUILDER_ROLES, MAX_BUILDER_RUN_DURATION_MS
} from "../src/run-ledger.js";

function clockAt(value) {
  let current = new Date(value);
  return {
    clock: () => new Date(current),
    advance: ms => { current = new Date(current.getTime() + ms); }
  };
}

test("creates a bounded run with all nine distinct Builder identities", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const ledger = new BuilderRunLedger({
    store: new MemoryStore(), clock: time.clock, idFactory: () => "overnight-001"
  });
  const run = await ledger.createRun({ scheduledFor: "2026-11-05T20:00:00Z" });
  assert.deepEqual(run.builders.map(item => item.role), BUILDER_ROLES);
  assert.equal(run.startedAt, null);
  assert.equal(run.deadlineAt, null);
  assert.equal(run.status, "queued");
  time.advance(30 * 60 * 1000);
  const started = await ledger.startRun(run.runId);
  assert.equal(new Date(started.deadlineAt).getTime() - new Date(started.startedAt).getTime(),
    MAX_BUILDER_RUN_DURATION_MS);
});

test("rejects work windows longer than eight hours", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  await assert.rejects(
    () => ledger.createRun({ durationMs: MAX_BUILDER_RUN_DURATION_MS + 1 }),
    /eight-hour maximum/
  );
});

test("persists Builder checkpoints and requires explicit safe resume after interruption", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock: time.clock });
  const run = await ledger.createRun({ runId: "resume-check", durationMs: 60 * 60 * 1000 });
  await ledger.startRun(run.runId);
  await ledger.startBuilder(run.runId, "HODR");
  await ledger.checkpointBuilder(run.runId, "HODR", {
    checkpointId: "hodr-step-1", data: { sourceCommit: "abc123", completed: 2 },
    safeToResume: false
  });
  await ledger.interruptRun(run.runId, "host process stopped");
  const recovered = await ledger.getRun(run.runId);
  assert.equal(recovered.builders.find(item => item.role === "HODR").status, "interrupted");
  await ledger.startRun(run.runId);
  await assert.rejects(() => ledger.startBuilder(run.runId, "HODR"), /no explicitly safe resume checkpoint/);
});

test("allows resumption only from a checkpoint explicitly marked safe", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const ledger = new BuilderRunLedger({ store: new MemoryStore(), clock: time.clock });
  const run = await ledger.createRun({ runId: "safe-resume", durationMs: 60 * 60 * 1000 });
  await ledger.startRun(run.runId);
  await ledger.startBuilder(run.runId, "MUNINN");
  await ledger.checkpointBuilder(run.runId, "MUNINN", {
    checkpointId: "muninn-safe", data: { artifactHash: "sha256:example" },
    safeToResume: true
  });
  await ledger.interruptRun(run.runId, "simulated process interruption");
  await ledger.startRun(run.runId);
  const resumed = await ledger.startBuilder(run.runId, "MUNINN");
  assert.equal(resumed.builders.find(item => item.role === "MUNINN").attempt, 2);
});

test("requires all other Builders to finish or be skipped before Baldr review", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  const run = await ledger.createRun({ runId: "baldr-gate" });
  await ledger.startRun(run.runId);
  await assert.rejects(() => ledger.startBuilder(run.runId, "BALDR"), /must wait/);
  for (const role of BUILDER_ROLES.filter(role => role !== "BALDR")) {
    await ledger.startBuilder(run.runId, role);
    await ledger.finishBuilder(run.runId, role, "succeeded");
  }
  const reviewed = await ledger.startBuilder(run.runId, "BALDR");
  assert.equal(reviewed.builders.find(item => item.role === "BALDR").status, "running");
});

test("requires explicit terminal outcomes before a run can complete", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  const run = await ledger.createRun({ runId: "complete-gate" });
  await ledger.startRun(run.runId);
  await assert.rejects(() => ledger.completeRun(run.runId), /pending, running, or interrupted/);
  for (const role of BUILDER_ROLES) {
    await ledger.startBuilder(run.runId, role);
    await ledger.finishBuilder(run.runId, role, role === "BALDR" ? "failed" : "succeeded",
      role === "BALDR" ? "review found unresolved issue" : null);
  }
  const completed = await ledger.completeRun(run.runId);
  assert.equal(completed.status, "completed_with_failures");
});

test("marks deadline expiry without replaying unfinished work", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const ledger = new BuilderRunLedger({ store: new MemoryStore(), clock: time.clock });
  const run = await ledger.createRun({ runId: "expiry", durationMs: 1000 });
  await ledger.startRun(run.runId);
  await ledger.startBuilder(run.runId, "ODIN");
  time.advance(1000);
  await assert.rejects(() => ledger.startBuilder(run.runId, "HODR"), /deadline has expired/);
  const expired = await ledger.markDeadlineExpired(run.runId);
  assert.equal(expired.status, "expired");
  assert.equal(expired.builders.find(item => item.role === "ODIN").status, "interrupted");
  assert.equal(expired.builders.find(item => item.role === "HODR").status, "interrupted");
});

test("rejects oversized checkpoint metadata", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  const run = await ledger.createRun({ runId: "checkpoint-size" });
  await ledger.startRun(run.runId);
  await ledger.startBuilder(run.runId, "HUGINN");
  await assert.rejects(() => ledger.checkpointBuilder(run.runId, "HUGINN", {
    checkpointId: "too-large", data: { payload: "x".repeat(17000) }
  }), /16 KiB/);
});

test("does not permit Baldr review to be silently skipped", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  const run = await ledger.createRun({ runId: "baldr-required" });
  await ledger.startRun(run.runId);
  for (const role of BUILDER_ROLES.filter(role => role !== "BALDR")) {
    await ledger.startBuilder(run.runId, role);
    await ledger.finishBuilder(run.runId, role, "succeeded");
  }
  await ledger.startBuilder(run.runId, "BALDR");
  await assert.rejects(
    () => ledger.finishBuilder(run.runId, "BALDR", "skipped"),
    /cannot be silently skipped/
  );
});

test("does not consume the eight-hour budget while a scheduled run is queued", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const ledger = new BuilderRunLedger({ store: new MemoryStore(), clock: time.clock });
  const run = await ledger.createRun({ runId: "queue-delay", durationMs: 60 * 60 * 1000 });
  time.advance(5 * 60 * 60 * 1000);
  const started = await ledger.startRun(run.runId);
  assert.equal(started.startedAt, "2026-11-06T01:00:00.000Z");
  assert.equal(started.deadlineAt, "2026-11-06T02:00:00.000Z");
});

import { registerBuilderRunHandler, BUILDER_RUN_HANDLER_TYPE } from "../src/builder-run-handler.js";

test("scheduler invokes all nine Builders through the injected executor and Baldr reviews last", async () => {
  const store = new MemoryStore();
  const scheduler = new Scheduler({ store, clock });
  const ledger = new BuilderRunLedger({ store, clock });
  const invoked = [];
  const output = [];
  registerBuilderRunHandler(scheduler, {
    ledger,
    executor: async (role, context) => {
      invoked.push(role);
      if (role === "BALDR") {
        assert.equal(context.outcomes.length, 8);
        assert.ok(context.outcomes.every(item => item.status === "succeeded"));
      }
      await context.checkpoint({
        checkpointId: role.toLowerCase() + "-complete",
        data: { role }, safeToResume: true
      });
      return { status: "succeeded", summary: role + " completed" };
    },
    output: line => output.push(JSON.parse(line))
  });
  await scheduler.addTask({
    id: "nine-builder-task",
    name: "Nine Builders scheduled run",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { durationMs: 60 * 60 * 1000 },
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.deepEqual(invoked, BUILDER_ROLES);
  assert.equal(output[0].event, "nine_builders_run_completed");
  const saved = await ledger.listRuns();
  assert.equal(saved[0].status, "completed");
  assert.equal(saved[0].builders.length, 9);
  assert.ok(saved[0].builders.every(item => item.status === "succeeded" && item.checkpoint));
});

test("scheduler records a Builder failure and still gives Baldr the full outcome set", async () => {
  const store = new MemoryStore();
  const scheduler = new Scheduler({ store, clock });
  const ledger = new BuilderRunLedger({ store, clock });
  let baldrOutcomes;
  registerBuilderRunHandler(scheduler, {
    ledger,
    executor: async (role, context) => {
      if (role === "HODR") throw new Error("simulated implementation failure");
      if (role === "BALDR") baldrOutcomes = context.outcomes;
      return { status: "succeeded" };
    },
    output: () => {}
  });
  await scheduler.addTask({
    id: "nine-builder-failure",
    name: "Nine Builders failure run",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.equal(baldrOutcomes.find(item => item.role === "HODR").status, "failed");
  assert.equal((await ledger.listRuns())[0].status, "completed_with_failures");
});

test("recovers abandoned active Builder runs without automatic replay", async () => {
  const time = clockAt("2026-11-05T20:00:00.000Z");
  const store = new MemoryStore();
  const first = new BuilderRunLedger({ store, clock: time.clock });
  const run = await first.createRun({ runId: "abandoned-run", durationMs: 60 * 60 * 1000 });
  await first.startRun(run.runId);
  await first.startBuilder(run.runId, "ODIN");
  const recovered = await new BuilderRunLedger({ store, clock: time.clock })
    .recoverAbandonedRuns("simulated host restart");
  assert.deepEqual(recovered, [run.runId]);
  const state = await first.getRun(run.runId);
  assert.equal(state.status, "interrupted");
  assert.equal(state.builders.find(item => item.role === "ODIN").status, "interrupted");
  await first.startRun(run.runId);
  await assert.rejects(() => first.startBuilder(run.runId, "ODIN"), /safe resume checkpoint/);
});

test("scheduler shutdown cooperatively cancels a Builder executor and preserves interruption", async () => {
  const store = new MemoryStore();
  const scheduler = new Scheduler({ store, clock });
  const ledger = new BuilderRunLedger({ store, clock });
  let started;
  const began = new Promise(resolve => { started = resolve; });
  registerBuilderRunHandler(scheduler, {
    ledger,
    executor: async (role, context) => {
      if (role === "ODIN") {
        started();
        await new Promise((resolve, reject) => {
          context.signal.addEventListener("abort", () => reject(new Error("cancelled")), { once: true });
        });
      }
      return { status: "succeeded" };
    },
    output: () => {}
  });
  await scheduler.addTask({
    id: "cancel-builder-run",
    name: "Cancellable Nine Builders run",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const execution = scheduler.runDue();
  await began;
  assert.equal(scheduler.abortActiveRuns("test shutdown"), 1);
  const result = await execution;
  assert.equal(result.runs[0].status, "failed");
  const saved = await ledger.listRuns();
  assert.equal(saved[0].status, "interrupted");
  assert.equal(saved[0].builders.find(item => item.role === "ODIN").status, "interrupted");
});
