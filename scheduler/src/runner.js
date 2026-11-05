import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { Scheduler } from "./scheduler.js";
import { registerGithubWatch } from "./github-watch.js";

const stateFile = resolve(process.env.SCHEDULER_STATE_FILE || "./data/scheduler.json");
const pollMs = Number(process.env.SCHEDULER_POLL_MS || 15000);
if (!Number.isInteger(pollMs) || pollMs < 1000 || pollMs > 300000) {
  throw new RangeError("SCHEDULER_POLL_MS must be between 1000 and 300000");
}

const scheduler = new Scheduler({
  store: new JsonFileStore(stateFile),
  maxRunsPerTick: 5
});

// Only fixed, reviewed handlers are registered here. Add real integrations as
// explicit modules with narrowly scoped credentials and approval requirements.
registerGithubWatch(scheduler);

scheduler.registerHandler("system.heartbeat", async (_payload, context) => {
  process.stdout.write(JSON.stringify({
    event: "scheduled_heartbeat",
    taskId: context.taskId,
    runId: context.runId,
    scheduledFor: context.scheduledFor,
    observedAt: new Date().toISOString()
  }) + "\n");
});

let stopping = false;
let tickActive = false;
let activeTick = Promise.resolve();

async function tick() {
  if (stopping || tickActive) return;
  tickActive = true;
  activeTick = scheduler.runDue();
  try {
    const result = await activeTick;
    for (const run of result.runs || []) {
      process.stdout.write(JSON.stringify({
        event: "scheduled_run",
        taskId: run.taskId,
        status: run.status,
        error: run.error
      }) + "\n");
    }
  } catch (error) {
    process.stderr.write(JSON.stringify({
      event: "scheduler_tick_error",
      error: String(error?.message || error)
    }) + "\n");
  } finally {
    tickActive = false;
  }
}

const tasks = await scheduler.listTasks();
process.stdout.write(JSON.stringify({
  event: "scheduler_started",
  stateFile,
  pollMs,
  taskCount: tasks.length,
  note: "Single-process local runner; only registered handlers can execute."
}) + "\n");

await tick();
const timer = setInterval(tick, pollMs);

async function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  clearInterval(timer);
  process.stdout.write(JSON.stringify({ event: "scheduler_stopping", signal }) + "\n");
  await activeTick;
  process.exitCode = 0;
}

process.once("SIGINT", () => { void shutdown("SIGINT"); });
process.once("SIGTERM", () => { void shutdown("SIGTERM"); });
