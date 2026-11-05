import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { Scheduler } from "./scheduler.js";
import { registerGithubWatch } from "./github-watch.js";
import { createDashboardServer } from "./dashboard-server.js";
import { acquireProcessLock } from "./process-lock.js";

const stateFile = resolve(process.env.SCHEDULER_STATE_FILE || "./data/scheduler.json");
const pollMs = Number(process.env.SCHEDULER_POLL_MS || 15000);
const dashboardPort = Number(process.env.SCHEDULER_DASHBOARD_PORT || 4174);
const dashboardHost = process.env.SCHEDULER_DASHBOARD_HOST || "127.0.0.1";
if (!Number.isInteger(pollMs) || pollMs < 1000 || pollMs > 300000) {
  throw new RangeError("SCHEDULER_POLL_MS must be between 1000 and 300000");
}

const releaseLock = await acquireProcessLock(stateFile + ".lock");
const scheduler = new Scheduler({
  store: new JsonFileStore(stateFile),
  maxRunsPerTick: 5
});
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

const dashboard = createDashboardServer({
  scheduler,
  host: dashboardHost,
  port: dashboardPort
});
let stopping = false;
let tickActive = false;
let activeTick = Promise.resolve();
let timer;

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

try {
  const address = await dashboard.listen();
  const tasks = await scheduler.listTasks();
  process.stdout.write(JSON.stringify({
    event: "scheduler_started",
    stateFile,
    pollMs,
    taskCount: tasks.length,
    dashboard: `http://127.0.0.1:${dashboardPort}`,
    note: "Single process, loopback-only dashboard, registered handlers only."
  }) + "\n");
  await tick();
  timer = setInterval(tick, pollMs);
} catch (error) {
  await releaseLock();
  throw error;
}

async function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  if (timer) clearInterval(timer);
  process.stdout.write(JSON.stringify({ event: "scheduler_stopping", signal }) + "\n");
  try {
    await activeTick;
    await dashboard.close();
  } finally {
    await releaseLock();
    process.exitCode = 0;
  }
}

process.once("SIGINT", () => { void shutdown("SIGINT"); });
process.once("SIGTERM", () => { void shutdown("SIGTERM"); });
