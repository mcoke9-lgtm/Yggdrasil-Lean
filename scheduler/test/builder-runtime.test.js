import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Scheduler } from "../src/scheduler.js";
import { MemoryStore } from "../src/store.js";
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
  await assert.rejects(() => registerOptionalBuilderRuntime({
    scheduler,
    schedulerStateFile: samePath,
    executorModulePath: "/tmp/host-executor.mjs",
    loadModule: async () => ({ createBuilderExecutor: async () => async () => ({ status: "succeeded" }) })
  }), /separate state file/);
});
