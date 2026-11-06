import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Scheduler } from "../src/scheduler.js";
import { MemoryStore, JsonFileStore } from "../src/store.js";
import { BuilderRunLedger } from "../src/run-ledger.js";
import { registerOptionalBuilderRuntime } from "../src/builder-runtime.js";
import { BUILDER_ROLES } from "../src/run-ledger.js";

test("leaves the persistent scheduler honest when no host executor is configured", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore() });
  const result = await registerOptionalBuilderRuntime({
    scheduler, schedulerStateFile: "/tmp/yggdrasil-test/scheduler.json",
    executorModulePath: null, output: () => {}
  });
  assert.equal(result.configured, false);
  assert.match(result.reason, /not configured/);
  await scheduler.addTask({
    id: "unconfigured",
    name: "No executor configured",
    handlerType: "nine-builders.run",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-05T19:00:00Z" }
  });
  const execution = await scheduler.runDue();
  assert.equal(execution.runs[0].status, "failed");
  assert.match(execution.runs[0].error, /No handler registered/);
});

test("registers a host executor and keeps its Builder ledger in a separate durable file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "yggdrasil-builder-runtime-"));
  try {
    const scheduler = new Scheduler({ store: new MemoryStore(), clock: () => new Date("2026-11-05T20:00:00Z") });
    const invoked = [];
    const result = await registerOptionalBuilderRuntime({
      scheduler,
      schedulerStateFile: join(directory, "scheduler.json"),
      executorModulePath: join(directory, "host-executor.mjs"),
      loadModule: async url => {
        assert.match(url, /^file:/);
        return {
          createBuilderExecutor: async () => async (role, context) => {
            invoked.push(role);
            assert.equal(context.task.taskReference, "vault:overnight-task-001");
            return { status: "succeeded", summary: "deterministic test executor" };
          }
        };
      },
      output: () => {}
    });
    assert.equal(result.configured, true);
    assert.equal(result.builderStateFile, join(directory, "scheduler.json.builder-runs.json"));
    await scheduler.addTask({
      id: "overnight",
      name: "Overnight Nine Builders test",
      handlerType: "nine-builders.run",
      payload: { taskReference: "vault:overnight-task-001" },
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-05T19:00:00Z" }
    });
    const execution = await scheduler.runDue();
    assert.equal(execution.runs[0].status, "succeeded");
    assert.deepEqual(invoked, BUILDER_ROLES);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("host startup recovers abandoned Builder work and explicit safe resume continues the same run", async () => {
  const directory = await mkdtemp(join(tmpdir(), "yggdrasil-builder-recovery-"));
  try {
    const schedulerStateFile = join(directory, "scheduler.json");
    const builderStateFilePath = join(directory, "builder-runs.json");
    const clock = () => new Date("2026-11-05T20:05:00.000Z");
    const seededLedger = new BuilderRunLedger({
      store: new JsonFileStore(builderStateFilePath),
      clock: () => new Date("2026-11-05T20:00:00.000Z"),
      idFactory: () => "builder:abandoned-001"
    });
    const abandoned = await seededLedger.createRun({
      runId: "builder:abandoned-001",
      scheduledFor: "2026-11-05T20:00:00.000Z"
    });
    await seededLedger.startRun(abandoned.runId);
    await seededLedger.startBuilder(abandoned.runId, "ODIN");
    await seededLedger.checkpointBuilder(abandoned.runId, "ODIN", {
      checkpointId: "odin-safe-checkpoint",
      data: { durable: true },
      safeToResume: true
    });

    const scheduler = new Scheduler({ store: new MemoryStore(), clock });
    const invoked = [];
    const events = [];
    const runtime = await registerOptionalBuilderRuntime({
      scheduler, schedulerStateFile, builderStateFilePath,
      executorModulePath: join(directory, "host-executor.mjs"),
      clock,
      loadModule: async () => ({
        createBuilderExecutor: async () => async role => {
          invoked.push(role);
          return { status: "succeeded", summary: "resumed after host recovery" };
        }
      }),
      output: line => events.push(JSON.parse(line))
    });
    assert.deepEqual(runtime.recoveredRuns, ["builder:abandoned-001"]);
    assert.equal(events[0].event, "nine_builders_abandoned_runs_recovered");

    const recoveredLedger = new BuilderRunLedger({
      store: new JsonFileStore(builderStateFilePath), clock
    });
    const recovered = await recoveredLedger.getRun("builder:abandoned-001");
    assert.equal(recovered.status, "interrupted");
    assert.equal(recovered.builders[0].status, "interrupted");
    assert.equal(recovered.builders[0].checkpoint.safeToResume, true);

    await scheduler.addTask({
      id: "resume-overnight",
      name: "Resume interrupted Nine Builders run",
      handlerType: "nine-builders.run",
      payload: { resumeRunId: "builder:abandoned-001", taskReference: "vault:task-001" },
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-05T20:00:00.000Z" }
    });
    const execution = await scheduler.runDue();
    assert.equal(execution.runs[0].status, "succeeded");
    assert.deepEqual(invoked, BUILDER_ROLES);
    const completed = await recoveredLedger.getRun("builder:abandoned-001");
    assert.equal(completed.status, "completed");
    assert.ok(completed.builders.every(builder => builder.status === "succeeded"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects a host module without the required executor factory", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore() });
  await assert.rejects(() => registerOptionalBuilderRuntime({
    scheduler,
    schedulerStateFile: "/tmp/yggdrasil-test/scheduler.json",
    executorModulePath: "/tmp/host-executor.mjs",
    loadModule: async () => ({})
  }), /must export createBuilderExecutor/);
});


test("rejects a shared scheduler and Builder ledger file", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore() });
  const samePath = "/tmp/yggdrasil-test/shared.json";
  let moduleLoaded = false;
  await assert.rejects(() => registerOptionalBuilderRuntime({
    scheduler,
    schedulerStateFile: samePath,
    builderStateFilePath: samePath,
    executorModulePath: "/tmp/host-executor.mjs",
    loadModule: async () => {
      moduleLoaded = true;
      return { createBuilderExecutor: async () => async () => ({ status: "succeeded" }) };
    }
  }), /separate state file/);
  assert.equal(moduleLoaded, false, "Invalid storage configuration must fail before loading host code");
});
