import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { Scheduler } from "./scheduler.js";
import { registerGithubWatch } from "./github-watch.js";
import { createDashboardServer } from "./dashboard-server.js";
import { acquireProcessLock } from "./process-lock.js";
import { registerOptionalBuilderRuntime } from "./builder-runtime.js";

const stateFile = resolve(process.env.SCHEDULER_STATE_FILE || "./data/scheduler.json");
const pollMs = Number(process.env.SCHEDULER_POLL_MS || 15000);
const dashboardPort = Number(process.env.SCHEDULER_DASHBOARD_PORT || 4174);
const dashboardHost = process.env.SCHEDULER_DASHBOARD_HOST || "127.0.0.1";
const leaseHeartbeatMs = Number(process.env.SCHEDULER_LEASE_HEARTBEAT_MS || 15000);
if (!Number.isInteger(leaseHeartbeatMs) || leaseHeartbeatMs < 1000 || leaseHeartbeatMs > 300000) {
  throw new RangeError("SCHEDULER_LEASE_HEARTBEAT_MS must be between 1000 and 300000");
}
if (!Number.isInteger(pollMs) || pollMs < 1000 || pollMs > 300000) {
  throw new RangeError("SCHEDULER_POLL_MS must be between 1000 and 300000");
}

const releaseLock = await acquireProcessLock(stateFile + ".lock");
const scheduler = new Scheduler({
  store: new JsonFileStore(stateFile),
  maxRunsPerTick: 5
});
registerGithubWatch(scheduler);
let builderRuntime;
try {
  builderRuntime = await registerOptionalBuilderRuntime({ scheduler, schedulerStateFile: stateFile });
} catch (error) {
  await releaseLock();
  throw error;
}
process.stdout.write(JSON.stringify({
  event: builderRuntime.configured ? "nine_builders_executor_registered" : "nine_builders_executor_unconfigured",
  reason: builderRuntime.reason ?? null,
  builderStateFile: builderRuntime.builderStateFile ?? null,
  recoveredBuilderRuns: builderRuntime.recoveredRuns ?? []
}) + "\n");
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
let leaseHeartbeatTimer;
let heartbeatInFlight = false;

// Install shutdown handlers before announcing readiness or awaiting the first tick.
// A SIGTERM delivered at the scheduler_started boundary must never be lost.
process.once("SIGINT", () => { void shutdown("SIGINT"); });
process.once("SIGTERM", () => { void shutdown("SIGTERM"); });

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
  if (stopping) {
    if (dashboard.server.listening) await dashboard.close();
  } else {
    leaseHeartbeatTimer = setInterval(async () => {
      if (stopping || heartbeatInFlight) return;
      heartbeatInFlight = true;
      try {
        await releaseLock.heartbeat();
      } catch (error) {
        process.stderr.write(JSON.stringify({
          event: "scheduler_lease_lost",
          error: String(error?.message || error)
        }) + "\n");
        scheduler.abortActiveRuns("Scheduler process lease ownership was lost");
        void shutdown("LOCK_LOST");
      } finally {
        heartbeatInFlight = false;
      }
    }, leaseHeartbeatMs);
    leaseHeartbeatTimer.unref?.();
    const tasks = await scheduler.listTasks();
    process.stdout.write(JSON.stringify({
      event: "scheduler_started",
      stateFile,
      pollMs,
      leaseHeartbeatMs,
      taskCount: tasks.length,
      dashboard: `http://127.0.0.1:${address.port}`,
      note: "Single process, loopback-only dashboard, registered handlers only."
    }) + "\n");
    await tick();
    // Shutdown may arrive while the initial tick is awaited. Do not recreate
    // the polling timer after shutdown has already cleared it.
    if (!stopping) timer = setInterval(tick, pollMs);
  }
} catch (error) {
  await releaseLock();
  throw error;
}

async function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  if (timer) clearInterval(timer);
  process.stdout.write(JSON.stringify({ event: "scheduler_stopping", signal }) + "\n");
  scheduler.abortActiveRuns("Scheduler received " + signal);
  try {
    await activeTick;
    if (dashboard.server.listening) await dashboard.close();
  } finally {
    await releaseLock();
    process.exitCode = signal === "LOCK_LOST" ? 1 : 0;
  }
}

