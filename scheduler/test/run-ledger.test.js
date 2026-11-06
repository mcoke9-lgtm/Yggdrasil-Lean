import test from "node:test";
import assert from "node:assert/strict";
import { MemoryStore } from "../src/store.js";
import { Scheduler } from "../src/scheduler.js";
import { registerBuilderRunHandler, BUILDER_RUN_HANDLER_TYPE } from "../src/builder-run-handler.js";
import {
  BuilderRunLedger, BUILDER_ROLES, MAX_BUILDER_RUN_DURATION_MS
} from "../src/run-ledger.js";

const clock = () => new Date("2026-11-04T12:00:00.000Z");

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
    payload: { durationMs: 60 * 60 * 1000, taskReference: "vault:nine-builder-task" },
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
    payload: { taskReference: "vault:nine-builder-failure" },
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.equal(baldrOutcomes.find(item => item.role === "HODR").status, "failed");
  assert.equal((await ledger.listRuns())[0].status, "completed_with_failures");
});

test("Builder interruption and improvement incident stop the round without failing the role", async () => {
  for (const scenario of [
    { status: "interrupted", turnType: "INTERRUPTION" },
    { status: "succeeded", turnType: "IMPROVEMENT_INCIDENT" }
  ]) {
    const store = new MemoryStore();
    const scheduler = new Scheduler({ store, clock });
    const ledger = new BuilderRunLedger({ store, clock });
    const invoked = [];
    const events = [];
    registerBuilderRunHandler(scheduler, {
      ledger,
      executor: async role => {
        invoked.push(role);
        if (role === "HODR") {
          return { ...scenario, summary: "Höðr requested a pause for review" };
        }
        return { status: "succeeded", summary: role + " completed" };
      },
      output: line => events.push(JSON.parse(line))
    });
    await scheduler.addTask({
      id: "stop-on-builder-event-" + scenario.turnType.toLowerCase(),
      name: "Stop on Builder event",
      handlerType: BUILDER_RUN_HANDLER_TYPE,
      payload: { taskReference: "vault:stop-on-" + scenario.turnType.toLowerCase() },
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
    });
    const result = await scheduler.runDue();
    assert.equal(result.runs[0].status, "failed");
    assert.deepEqual(invoked, ["ODIN", "HODR"]);
    const saved = (await ledger.listRuns())[0];
    assert.equal(saved.status, "interrupted");
    assert.equal(saved.builders.find(item => item.role === "ODIN").status, "succeeded");
    assert.equal(saved.builders.find(item => item.role === "HODR").status, "interrupted");
    assert.equal(saved.builders.find(item => item.role === "LOKI").status, "pending");
    assert.ok(events.some(event => event.event === "nine_builders_run_interrupted"
      && event.role === "HODR" && event.turnType === scenario.turnType));
  }
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
    clock,
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
    payload: { taskReference: "vault:cancel-run" },
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

test("resumed Baldr review receives the original saved summaries from completed Builders", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const prior = await ledger.createRun({
    runId: "summary-resume-run",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:summary-resume"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.finishBuilder(prior.runId, "ODIN", "succeeded", null,
    "Odin reconciled the original evidence set.");
  await ledger.startBuilder(prior.runId, "HODR");
  await ledger.checkpointBuilder(prior.runId, "HODR", {
    checkpointId: "hodr-safe-resume", safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "simulated restart", "HODR");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  let baldrOutcomes = null;
  registerBuilderRunHandler(scheduler, {
    ledger, clock,
    executor: async (role, context) => {
      invoked.push(role);
      if (role === "BALDR") baldrOutcomes = context.outcomes;
      return { status: "succeeded", summary: role + " completed after resume" };
    },
    output: () => {}
  });
  await scheduler.addTask({
    id: "resume-with-summaries",
    name: "Resume with preserved summaries",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId },
    schedule: { kind: "once", startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.equal(invoked.includes("ODIN"), false);
  assert.equal(invoked[0], "HODR");
  assert.equal(invoked.at(-1), "BALDR");
  assert.equal(baldrOutcomes.length, 8);
  assert.equal(
    baldrOutcomes.find(item => item.role === "ODIN").summary,
    "Odin reconciled the original evidence set."
  );
});

test("requires a task reference and prevents resume from rebinding a Builder run to another vault task", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  registerBuilderRunHandler(scheduler, {
    ledger,
    clock,
    executor: async role => {
      invoked.push(role);
      return { status: "succeeded" };
    },
    output: () => {}
  });

  await scheduler.addTask({
    id: "missing-task-reference",
    name: "Missing vault task reference",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const missing = await scheduler.runDue();
  assert.equal(missing.runs[0].status, "failed");
  assert.match(missing.runs[0].error, /taskReference is required/);
  assert.equal((await ledger.listRuns()).length, 0);

  const prior = await ledger.createRun({
    runId: "task-bound-resume",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:original-task"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.checkpointBuilder(prior.runId, "ODIN", {
    checkpointId: "odin-bound-checkpoint", safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "simulated restart");
  await scheduler.addTask({
    id: "wrong-task-reference-resume",
    name: "Wrong task reference resume",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId, taskReference: "vault:other-task" },
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const wrong = await scheduler.runDue();
  assert.equal(wrong.runs[0].status, "failed");
  assert.match(wrong.runs[0].error, /does not match the original Builder run/);
  assert.deepEqual(invoked, []);
  assert.equal((await ledger.getRun(prior.runId)).status, "interrupted");
});

test("recovers an interrupted Builder from its durable vault result without invoking it again", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const prior = await ledger.createRun({
    runId: "durable-recovery-run",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:durable-recovery"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.interruptRun(prior.runId, "simulated process loss", "ODIN");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  let baldrOutcomes = null;
  const executor = async (role, context) => {
    invoked.push(role);
    if (role === "BALDR") baldrOutcomes = context.outcomes;
    return { status: "succeeded", summary: role + " completed after recovery" };
  };
  executor.recoverDurableResult = async (role, context) => {
    assert.equal(role, "ODIN");
    assert.equal(context.runId, prior.runId);
    assert.equal(context.task.taskReference, "vault:durable-recovery");
    return {
      checkpointId: "vault-checkpoint-odin",
      data: { responseSha256: "a".repeat(64), recoveredFromEncryptedVault: true },
      summary: "Odin recovered its durable vault result without another model request.",
      turnType: "CONTRIBUTION",
      status: "succeeded"
    };
  };
  registerBuilderRunHandler(scheduler, { ledger, clock, executor, output: () => {} });
  await scheduler.addTask({
    id: "resume-durable-recovery",
    name: "Resume from durable vault result",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId },
    schedule: { kind: "once", startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.equal(invoked.includes("ODIN"), false);
  assert.equal(invoked[0], "HODR");
  assert.equal(invoked.at(-1), "BALDR");
  assert.equal(baldrOutcomes.length, 8);
  assert.match(
    baldrOutcomes.find(item => item.role === "ODIN").summary,
    /recovered its durable vault result/
  );
  const saved = await ledger.getRun(prior.runId);
  const odin = saved.builders.find(item => item.role === "ODIN");
  assert.equal(odin.status, "succeeded");
  assert.equal(odin.checkpoint.safeToResume, true);
  assert.equal(odin.checkpoint.data.recoveredFromDurableResult, true);
});

test("scheduled handler resumes an interrupted run only from safe checkpoints", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const prior = await ledger.createRun({
    runId: "resumable-builder-run",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:resumable-task"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.checkpointBuilder(prior.runId, "ODIN", {
    checkpointId: "odin-resume-point", data: { completedWorkUnits: 3 },
    safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "simulated host restart");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  registerBuilderRunHandler(scheduler, {
    ledger, clock,
    executor: async role => {
      invoked.push(role);
      return { status: "succeeded", summary: "resumed or continued" };
    },
    output: () => {}
  });
  await scheduler.addTask({
    id: "resume-existing-builder-run",
    name: "Resume existing Builder run",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId },
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.equal(invoked[0], "ODIN");
  assert.equal(invoked[8], "BALDR");
  const saved = await ledger.getRun(prior.runId);
  assert.equal(saved.status, "completed");
  assert.equal(saved.builders.find(item => item.role === "ODIN").attempt, 2);
});
