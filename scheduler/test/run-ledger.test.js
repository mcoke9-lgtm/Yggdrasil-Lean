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
  assert.equal(run.schemaVersion, 2);
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
    checkpointId: "hodr-step-1", safeToResume: false
  });
  await ledger.interruptRun(run.runId, "HOST_INTERRUPTED");
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
    checkpointId: "muninn-safe", responseSha256: "a".repeat(64), safeToResume: true
  });
  await ledger.interruptRun(run.runId, "HOST_INTERRUPTED");
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
    await ledger.finishBuilder(run.runId, role, "succeeded", { outcomeRef: role.toLowerCase() + "-turn" });
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
      role === "BALDR" ? { errorCode: "EXECUTOR_FAILURE" } : { outcomeRef: role.toLowerCase() + "-turn" });
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
  }), /unrecognized field/);
});

test("does not permit Baldr review to be silently skipped", async () => {
  const ledger = new BuilderRunLedger({ store: new MemoryStore() });
  const run = await ledger.createRun({ runId: "baldr-required" });
  await ledger.startRun(run.runId);
  for (const role of BUILDER_ROLES.filter(role => role !== "BALDR")) {
    await ledger.startBuilder(run.runId, role);
    await ledger.finishBuilder(run.runId, role, "succeeded", { outcomeRef: role.toLowerCase() + "-turn" });
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
        safeToResume: true
      });
      return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
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
  const emitted = [];
  registerBuilderRunHandler(scheduler, {
    ledger,
    executor: async (role, context) => {
      if (role === "HODR") throw new Error("PRIVATE PROVIDER FAILURE TEXT");
      if (role === "BALDR") baldrOutcomes = context.outcomes;
      return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
    },
    output: line => emitted.push(JSON.parse(line))
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
  const saved = (await ledger.listRuns())[0];
  const failedBuilder = saved.builders.find(item => item.role === "HODR");
  assert.equal(failedBuilder.errorCode, "EXECUTOR_FAILURE");
  assert.equal(Object.hasOwn(failedBuilder, "error"), false);
  assert.equal(Object.hasOwn(failedBuilder, "summary"), false);
  assert.equal(Object.hasOwn(saved, "interruptionReason"), false);
  assert.equal(JSON.stringify(emitted).includes("PRIVATE PROVIDER FAILURE TEXT"), false);
  assert.equal(Object.hasOwn(emitted[0].outcomes.find(item => item.role === "HODR"), "summary"), false);
  assert.equal(Object.hasOwn(emitted[0].outcomes.find(item => item.role === "HODR"), "error"), false);
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
          return { ...scenario };
        }
        return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
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
    const interruptionEvent = events.find(event => event.event === "nine_builders_run_interrupted");
    assert.equal(interruptionEvent.errorCode, "BUILDER_REQUESTED_INTERRUPTION");
    assert.equal(Object.hasOwn(interruptionEvent, "reason"), false);
    assert.equal(Object.hasOwn(saved, "interruptionReason"), false);
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
      return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
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

test("resumed Baldr review receives typed references from completed Builders", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const prior = await ledger.createRun({
    runId: "summary-resume-run",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:metadata-resume"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.finishBuilder(prior.runId, "ODIN", "succeeded", {
    outcomeRef: "vault-odin", outcomeType: "CONTRIBUTION", evidenceStatus: "OBSERVED",
    responseSha256: "a".repeat(64)
  });
  await ledger.startBuilder(prior.runId, "HODR");
  await ledger.checkpointBuilder(prior.runId, "HODR", {
    checkpointId: "hodr-safe-resume", safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "HOST_INTERRUPTED", "HODR");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  let baldrOutcomes = null;
  const executor = async (role, context) => {
    invoked.push(role);
    if (role === "HODR") assert.equal(context.resumeCheckpoint.vaultTurnId, "hodr-safe-resume");
    if (role === "BALDR") baldrOutcomes = context.outcomes;
    return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
  };
  executor.supportsVerifiedResumeCheckpoint = true;
  registerBuilderRunHandler(scheduler, {
    ledger, clock, executor, output: () => {}
  });
  await scheduler.addTask({
    id: "resume-with-metadata",
    name: "Resume with preserved references",
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
    baldrOutcomes.find(item => item.role === "ODIN").outcomeRef,
    "vault-odin"
  );
});

test("new Builder runs use schema v2 metadata without free-text fields", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const run = await ledger.createRun({
    runId: "metadata-only-v2",
    taskReference: "vault:metadata-only"
  });
  assert.equal(run.schemaVersion, 2);
  assert.equal(Object.hasOwn(run, "interruptionReason"), false);
  assert.equal(Object.hasOwn(run.builders[0], "summary"), false);
  assert.equal(Object.hasOwn(run.builders[0], "error"), false);
  await ledger.startRun(run.runId);
  await ledger.startBuilder(run.runId, "ODIN");
  await assert.rejects(() => ledger.checkpointBuilder(run.runId, "ODIN", {
    checkpointId: "checkpoint-free-text", safeToResume: true,
    summary: "must never enter the plaintext ledger"
  }), /unrecognized field/);
  await assert.rejects(() => ledger.finishBuilder(run.runId, "ODIN", "succeeded"),
    /outcomeRef/);
});

test("version-1 runs remain readable and immutable", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const created = await ledger.createRun({
    runId: "legacy-v1-read-only", taskReference: "vault:legacy-v1"
  });
  await ledger.startRun(created.runId);
  await ledger.startBuilder(created.runId, "ODIN");
  await ledger.interruptRun(created.runId, "HOST_INTERRUPTED", "ODIN");
  const state = await store.load();
  const legacy = state.builderRuns.find(item => item.runId === created.runId);
  legacy.schemaVersion = 1;
  legacy.interruptionReason = "historical plaintext reason";
  legacy.builders[0].summary = "historical plaintext summary";
  legacy.builders[0].error = "historical plaintext error";
  await store.save(state);
  const before = await ledger.getRun(created.runId);
  assert.equal(before.schemaVersion, 1);
  assert.equal(before.builders[0].summary, "historical plaintext summary");
  await assert.rejects(() => ledger.startRun(created.runId), /read-only/);
  await assert.rejects(() => ledger.finishBuilder(created.runId, "ODIN", "failed", {
    errorCode: "EXECUTOR_FAILURE"
  }), /read-only/);
  assert.deepEqual(await ledger.recoverAbandonedRuns(), []);
  assert.deepEqual(await ledger.getRun(created.runId), before);
});

test("explicit v1 continuation forks history into a separate v2 run without mutating v1", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const source = await ledger.createRun({
    runId: "legacy-v1-continue", taskReference: "vault:legacy-continue"
  });
  await ledger.startRun(source.runId);
  await ledger.startBuilder(source.runId, "ODIN");
  await ledger.checkpointBuilder(source.runId, "ODIN", {
    checkpointId: "legacy-odin-turn", responseSha256: "a".repeat(64),
    turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", safeToResume: true
  });
  await ledger.interruptRun(source.runId, "HOST_INTERRUPTED", "ODIN");
  const state = await store.load();
  const legacy = state.builderRuns.find(item => item.runId === source.runId);
  legacy.schemaVersion = 1;
  legacy.builders[0].status = "succeeded";
  legacy.builders[0].checkpoint = {
    checkpointId: "legacy-odin-turn", data: { responseSha256: "a".repeat(64) },
    safeToResume: true, recordedAt: "2026-11-04T12:00:00.000Z"
  };
  legacy.builders[0].summary = "legacy summary stays in v1";
  legacy.builders[0].error = null;
  legacy.builders.find(item => item.role === "MARI").status = "skipped";
  await store.save(state);
  const legacyBefore = await ledger.getRun(source.runId);

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  const executor = async (role) => {
    invoked.push(role);
    return {
      status: "succeeded",
      outcomeRef: role === "ODIN" ? "legacy-odin-turn" : role.toLowerCase() + "-new-turn",
      turnType: "CONTRIBUTION",
      evidenceStatus: "OBSERVED",
      responseSha256: "b".repeat(64)
    };
  };
  executor.forkRunHistory = async args => {
    assert.equal(args.sourceRunId, source.runId);
    assert.equal(args.taskReference, "vault:legacy-continue");
    assert.equal(args.sourceBuilders.find(item => item.role === "ODIN").checkpoint.checkpointId,
      "legacy-odin-turn");
    return {
      durable: true,
      sourceHistorySha256: "c".repeat(64),
      sourceThreadId: "nine-builders:" + source.runId,
      targetThreadId: "nine-builders:builder:continuation:test",
      importedTurnCount: 1
    };
  };
  registerBuilderRunHandler(scheduler, { ledger, clock, executor, output: () => {} });
  await scheduler.addTask({
    id: "continue-legacy-run",
    name: "Continue legacy run",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { continueRunId: source.runId },
    schedule: { kind: "once", startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "succeeded");
  assert.deepEqual(invoked, BUILDER_ROLES.filter(role => role !== "MARI"));
  const allRuns = await ledger.listRuns();
  const continuation = allRuns.find(item => item.schemaVersion === 2 &&
    item.supersedesRunId === source.runId);
  assert.ok(continuation);
  assert.equal(Object.hasOwn(continuation, "sourceHistorySha256"), false);
  assert.equal(continuation.taskReference, "vault:legacy-continue");
  const carriedSkip = continuation.builders.find(item => item.role === "MARI");
  assert.equal(carriedSkip.status, "skipped");
  assert.equal(carriedSkip.attempt, 0);
  assert.deepEqual(await ledger.getRun(source.runId), legacyBefore);
});

test("manual reconciliation errors stop a resumed v2 run before later Builders", async () => {
  const store = new MemoryStore();
  const ledger = new BuilderRunLedger({ store, clock });
  const prior = await ledger.createRun({
    runId: "manual-resume-run",
    scheduledFor: "2026-11-04T12:00:00Z",
    durationMs: 60 * 60 * 1000,
    taskReference: "vault:manual-resume"
  });
  await ledger.startRun(prior.runId);
  await ledger.startBuilder(prior.runId, "ODIN");
  await ledger.checkpointBuilder(prior.runId, "ODIN", {
    checkpointId: "missing-durable-turn",
    responseSha256: "a".repeat(64),
    turnType: "CONTRIBUTION",
    evidenceStatus: "OBSERVED",
    safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "HOST_INTERRUPTED", "ODIN");
  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  const events = [];
  const executor = async role => {
    invoked.push(role);
    const error = new Error("MANUAL_RECONCILIATION_REQUIRED");
    error.code = "MANUAL_RECONCILIATION_REQUIRED";
    throw error;
  };
  executor.supportsVerifiedResumeCheckpoint = true;
  registerBuilderRunHandler(scheduler, {
    ledger, clock, executor, output: line => events.push(JSON.parse(line))
  });
  await scheduler.addTask({
    id: "resume-manual-check",
    name: "Resume with missing durable turn",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId },
    schedule: { kind: "once", startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "failed");
  assert.deepEqual(invoked, ["ODIN"]);
  const saved = await ledger.getRun(prior.runId);
  assert.equal(saved.status, "interrupted");
  assert.equal(saved.interruptionCode, "MANUAL_RECONCILIATION_REQUIRED");
  assert.equal(saved.builders.find(item => item.role === "ODIN").errorCode,
    "MANUAL_RECONCILIATION_REQUIRED");
  assert.equal(saved.builders.find(item => item.role === "HODR").status, "pending");
  assert.equal(events[0].errorCode, "MANUAL_RECONCILIATION_REQUIRED");
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
      return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
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
  await ledger.interruptRun(prior.runId, "HOST_INTERRUPTED");
  await scheduler.addTask({
    id: "wrong-task-reference-resume",
    name: "Wrong task reference resume",
    handlerType: BUILDER_RUN_HANDLER_TYPE,
    payload: { resumeRunId: prior.runId, taskReference: "vault:other-task" },
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const wrong = await scheduler.runDue();
  assert.equal(wrong.runs[0].status, "failed");
  assert.match(wrong.runs[0].error, /MANUAL_RECONCILIATION_REQUIRED/);
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
  await ledger.interruptRun(prior.runId, "HOST_INTERRUPTED", "ODIN");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  let baldrOutcomes = null;
  const executor = async (role, context) => {
    invoked.push(role);
    if (role === "BALDR") baldrOutcomes = context.outcomes;
    return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
  };
  executor.recoverDurableResult = async (role, context) => {
    assert.equal(role, "ODIN");
    assert.equal(context.runId, prior.runId);
    assert.equal(context.task.taskReference, "vault:durable-recovery");
    return {
      checkpointId: "vault-checkpoint-odin",
      responseSha256: "a".repeat(64),
      turnType: "CONTRIBUTION",
      evidenceStatus: "OBSERVED",
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
  assert.equal(
    baldrOutcomes.find(item => item.role === "ODIN").outcomeRef,
    "vault-checkpoint-odin"
  );
  const saved = await ledger.getRun(prior.runId);
  const odin = saved.builders.find(item => item.role === "ODIN");
  assert.equal(odin.status, "succeeded");
  assert.equal(odin.checkpoint.safeToResume, true);
  assert.equal(odin.checkpoint.vaultTurnId, "vault-checkpoint-odin");
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
    checkpointId: "odin-resume-point", responseSha256: "a".repeat(64),
    safeToResume: true
  });
  await ledger.interruptRun(prior.runId, "HOST_INTERRUPTED");

  const scheduler = new Scheduler({ store, clock });
  const invoked = [];
  const executor = async (role, context) => {
    invoked.push(role);
    if (role === "ODIN") assert.equal(context.resumeCheckpoint.vaultTurnId, "odin-resume-point");
    return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
  };
  executor.supportsVerifiedResumeCheckpoint = true;
  registerBuilderRunHandler(scheduler, {
    ledger, clock, executor, output: () => {}
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
