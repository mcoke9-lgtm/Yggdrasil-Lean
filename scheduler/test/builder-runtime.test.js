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
            return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
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
      scheduledFor: "2026-11-05T20:00:00.000Z",
      taskReference: "vault:task-001"
    });
    await seededLedger.startRun(abandoned.runId);
    await seededLedger.startBuilder(abandoned.runId, "ODIN");
    await seededLedger.checkpointBuilder(abandoned.runId, "ODIN", {
      checkpointId: "odin-safe-checkpoint",
      responseSha256: "a".repeat(64),
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
        createBuilderExecutor: async () => {
          const executor = async role => {
            invoked.push(role);
            return { status: "succeeded", outcomeRef: role.toLowerCase() + "-turn", turnType: "CONTRIBUTION", evidenceStatus: "OBSERVED", responseSha256: "a".repeat(64) };
          };
          executor.supportsVerifiedResumeCheckpoint = true;
          return executor;
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

import { configureDedicatedBuilderRunner } from "../src/builder-runner-config.js";

test("dedicated runner requires explicit separate state, lock, and dashboard port", () => {
  const env = {
    NINE_BUILDERS_SCHEDULER_STATE_FILE: "/tmp/yggdrasil/nine-builders.json",
    NINE_BUILDERS_DASHBOARD_PORT: "4175",
    BUILDER_EXECUTOR_MODULE: "/tmp/yggdrasil/host/executor.mjs"
  };
  const configured = configureDedicatedBuilderRunner(env, "/tmp");
  assert.equal(configured.schedulerStateFile, "/tmp/yggdrasil/nine-builders.json");
  assert.equal(configured.schedulerLockFile, "/tmp/yggdrasil/nine-builders.json.lock");
  assert.equal(configured.dashboardPort, 4175);
  assert.equal(env.SCHEDULER_STATE_FILE, configured.schedulerStateFile);
  assert.equal(env.SCHEDULER_DASHBOARD_PORT, "4175");
  assert.equal(env.BUILDER_EXECUTOR_MODULE, "/tmp/yggdrasil/host/executor.mjs");
});

test("dedicated runner refuses missing configuration and shared state or dashboard port", () => {
  assert.throws(() => configureDedicatedBuilderRunner({}, "/tmp"), /NINE_BUILDERS_SCHEDULER_STATE_FILE is required/);
  assert.throws(() => configureDedicatedBuilderRunner({
    NINE_BUILDERS_SCHEDULER_STATE_FILE: "./data/scheduler.json",
    NINE_BUILDERS_DASHBOARD_PORT: "4175",
    BUILDER_EXECUTOR_MODULE: "./host.mjs"
  }, "/tmp"), /must differ from the general scheduler state file/);
  assert.throws(() => configureDedicatedBuilderRunner({
    NINE_BUILDERS_SCHEDULER_STATE_FILE: "./data/nine-builders.json",
    NINE_BUILDERS_DASHBOARD_PORT: "4174",
    BUILDER_EXECUTOR_MODULE: "./host.mjs"
  }, "/tmp"), /dashboard port must differ/);
  assert.throws(() => configureDedicatedBuilderRunner({
    NINE_BUILDERS_SCHEDULER_STATE_FILE: "./data/nine-builders.json",
    NINE_BUILDERS_DASHBOARD_PORT: "0",
    BUILDER_EXECUTOR_MODULE: "./host.mjs"
  }, "/tmp"), /between 1 and 65535/);
});
