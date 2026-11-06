import test from "node:test";
import { spawn } from "node:child_process";
import { request as httpRequest } from "node:http";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { registerGithubWatch } from "../src/github-watch.js";
import { JsonFileStore, MemoryStore } from "../src/store.js";
import { Scheduler } from "../src/scheduler.js";
import { createDashboardServer } from "../src/dashboard-server.js";
import { acquireProcessLock } from "../src/process-lock.js";

const clock = () => new Date("2026-11-04T12:00:00.000Z");

test("creates and lists a recurring task", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  const task = await scheduler.addTask({
    id: "daily-check",
    name: "Daily check",
    handlerType: "test.check",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  assert.equal(task.id, "daily-check");
  assert.equal((await scheduler.listTasks()).length, 1);
});

test("runs a due handler and records success", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  let called = false;
  scheduler.registerHandler("test.check", async () => { called = true; });
  await scheduler.addTask({
    id: "run-check",
    name: "Run check",
    handlerType: "test.check",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(called, true);
  assert.equal(result.runs[0].status, "succeeded");
});

test("runs a one-time task once, then disables it with no next run", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  let calls = 0;
  scheduler.registerHandler("test.once", async () => { calls += 1; });
  const task = await scheduler.addTask({
    id: "one-time-check",
    name: "One-time check",
    handlerType: "test.once",
    schedule: { kind: "once", startAt: "2026-11-04T12:00:00Z" }
  });
  assert.equal(task.schedule.kind, "once");
  assert.equal(task.nextRunAt, "2026-11-04T12:00:00.000Z");
  const first = await scheduler.runDue();
  assert.equal(first.runs[0].status, "succeeded");
  assert.equal(calls, 1);
  const saved = (await scheduler.listTasks())[0];
  assert.equal(saved.enabled, false);
  assert.equal(saved.nextRunAt, null);
  const second = await scheduler.runDue();
  assert.deepEqual(second.runs, []);
  assert.equal(calls, 1);
  await assert.rejects(() => scheduler.setEnabled("one-time-check", true),
    /completed one-time task cannot be re-enabled/);
});

test("does not run a one-time task before its start time", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  let calls = 0;
  scheduler.registerHandler("test.once", async () => { calls += 1; });
  await scheduler.addTask({
    id: "future-one-time",
    name: "Future one-time check",
    handlerType: "test.once",
    schedule: { kind: "once", startAt: "2026-11-04T12:01:00Z" }
  });
  const result = await scheduler.runDue();
  assert.deepEqual(result.runs, []);
  assert.equal(calls, 0);
});

test("records a failed handler", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  scheduler.registerHandler("test.fail", async () => { throw new Error("expected failure"); });
  await scheduler.addTask({
    id: "failure-check",
    name: "Failure check",
    handlerType: "test.fail",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const result = await scheduler.runDue();
  assert.equal(result.runs[0].status, "failed");
  assert.match(result.runs[0].error, /expected failure/);
});

test("rejects unsupported schedule kinds", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore() });
  await assert.rejects(() => scheduler.addTask({
    id: "unsupported",
    name: "Unsupported",
    handlerType: "test.check",
    schedule: { kind: "cron" }
  }), /Schedule kind must be interval, calendar, or once/);
});

test("supports daily wall-clock schedules in an IANA timezone", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  const task = await scheduler.addTask({
    id: "daily-local",
    name: "Daily local task",
    handlerType: "test.check",
    schedule: { kind: "calendar", frequency: "daily", hour: 9, minute: 0, timeZone: "America/New_York", startAt: "2026-11-04T12:00:00Z" }
  });
  assert.equal(task.nextRunAt, "2026-11-04T14:00:00.000Z");
});

test("skips nonexistent spring-forward wall times", async () => {
  const { nextCalendarOccurrence } = await import("../src/calendar.js");
  const schedule = { kind: "calendar", frequency: "daily", hour: 2, minute: 30, timeZone: "America/New_York" };
  assert.equal(
    nextCalendarOccurrence(schedule, "2026-03-08T05:00:00.000Z"),
    "2026-03-09T06:30:00.000Z"
  );
});

test("does not run a repeated fall-back wall time twice on one local date", async () => {
  const { nextCalendarOccurrence } = await import("../src/calendar.js");
  const schedule = { kind: "calendar", frequency: "daily", hour: 1, minute: 30, timeZone: "America/New_York" };
  assert.equal(
    nextCalendarOccurrence(schedule, "2026-11-01T05:30:00.000Z"),
    "2026-11-02T06:30:00.000Z"
  );
});

test("supports weekly schedules on selected local weekdays", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  const task = await scheduler.addTask({
    id: "weekly-monday",
    name: "Monday task",
    handlerType: "test.check",
    schedule: { kind: "calendar", frequency: "weekly", daysOfWeek: [1], hour: 9, minute: 0, timeZone: "America/New_York", startAt: "2026-11-04T12:00:00Z" }
  });
  assert.equal(task.nextRunAt, "2026-11-09T14:00:00.000Z");
});

test("GitHub watch baselines existing runs and reports only new failures", async () => {
  const directory = await mkdtemp(join(tmpdir(), "github-watch-"));
  const cursorPath = join(directory, "cursor.json");
  const output = [];
  let responseRuns = [
    { id: 10, name: "Analysis Engine", head_branch: "prototype", status: "completed", conclusion: "success" }
  ];
  const fakeFetch = async () => ({
    ok: true,
    json: async () => ({ workflow_runs: responseRuns })
  });
  try {
    const scheduler = new Scheduler({ store: new MemoryStore(), clock });
    registerGithubWatch(scheduler, { fetchImpl: fakeFetch, output: line => output.push(JSON.parse(line)), cursorPath });
    await scheduler.addTask({
      id: "github-watch",
      name: "GitHub watch",
      handlerType: "github.workflow-check",
      payload: { repository: "owner/repo", branch: "prototype", workflowNames: ["Analysis Engine"] },
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
    });
    await scheduler.runDue();
    assert.equal(output[0].event, "github_watch_baseline_created");

    responseRuns = [
      ...responseRuns,
      { id: 11, name: "Analysis Engine", head_branch: "prototype", status: "completed", conclusion: "failure", head_sha: "abc123", html_url: "https://github.com/owner/repo/actions/runs/11" }
    ];
    await scheduler.setEnabled("github-watch", false);
    const second = new Scheduler({ store: new MemoryStore(), clock });
    registerGithubWatch(second, { fetchImpl: fakeFetch, output: line => output.push(JSON.parse(line)), cursorPath });
    await second.addTask({
      id: "github-watch-next",
      name: "GitHub watch next",
      handlerType: "github.workflow-check",
      payload: { repository: "owner/repo", branch: "prototype", workflowNames: ["Analysis Engine"] },
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
    });
    const secondResult = await second.runDue();
    assert.equal(output.filter(item => item.event === "github_build_failure").length, 1);
    assert.equal(output.find(item => item.event === "github_build_failure").runId, 11);
    assert.equal(secondResult.runs[0].status, "failed");
    assert.match(secondResult.runs[0].error, /Analysis Engine #11/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("persists task definitions and run history across store instances", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-store-"));
  const filePath = join(directory, "state.json");
  try {
    const first = new Scheduler({ store: new JsonFileStore(filePath), clock });
    await first.addTask({
      id: "persistent-task",
      name: "Persistent task",
      handlerType: "test.check",
      schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
    });
    const disk = JSON.parse(await readFile(filePath, "utf8"));
    assert.equal(disk.tasks[0].id, "persistent-task");
    const second = new Scheduler({ store: new JsonFileStore(filePath), clock });
    assert.equal((await second.listTasks())[0].id, "persistent-task");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("pause and resume preserve the next scheduled time", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  await scheduler.addTask({
    id: "pause-test",
    name: "Pause test",
    handlerType: "test.check",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const paused = await scheduler.setEnabled("pause-test", false);
  assert.equal(paused.enabled, false);
  const resumed = await scheduler.setEnabled("pause-test", true);
  assert.equal(resumed.enabled, true);
  assert.equal(resumed.nextRunAt, "2026-11-04T12:00:00.000Z");
});

test("marks in-flight runs interrupted after restart without replay", async () => {
  const store = new MemoryStore({
    version: 1,
    tasks: [],
    runs: [{
      id: "task:2026-11-04T11:00:00.000Z",
      taskId: "task",
      handlerType: "test.check",
      scheduledFor: "2026-11-04T11:00:00.000Z",
      startedAt: "2026-11-04T11:00:00.000Z",
      finishedAt: null,
      status: "running",
      error: null
    }]
  });
  const scheduler = new Scheduler({ store, clock });
  await scheduler.runDue();
  const runs = await scheduler.listRuns();
  assert.equal(runs[0].status, "interrupted");
  assert.match(runs[0].error, /automatic replay is disabled/);
});

test("dashboard exposes local task management and rejects hostile hosts", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  const dashboard = createDashboardServer({ scheduler, port: 0 });
  const address = await dashboard.listen();
  const base = `http://127.0.0.1:${address.port}`;
  try {
    const page = await fetch(base + "/");
    assert.equal(page.status, 200);
    assert.match(page.headers.get("content-security-policy"), /default-src 'self'/);
    const created = await fetch(base + "/api/tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: "dashboard-watch",
        name: "Dashboard watch",
        repository: "owner/repo",
        branch: "main",
        workflowNames: ["Build"],
        timeZone: "America/New_York",
        frequency: "daily",
        hour: 9,
        minute: 0
      })
    });
    assert.equal(created.status, 201);
    const task = await created.json();
    assert.equal(task.handlerType, "github.workflow-check");
    const paused = await fetch(base + "/api/tasks/dashboard-watch", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: false })
    });
    assert.equal((await paused.json()).enabled, false);
    const hostileStatus = await new Promise((resolveHostile, rejectHostile) => {
      const request = httpRequest({
        hostname: "127.0.0.1",
        port: address.port,
        path: "/api/tasks",
        method: "GET",
        headers: { Host: "attacker.example" }
      }, response => {
        response.resume();
        resolveHostile(response.statusCode);
      });
      request.once("error", rejectHostile);
      request.end();
    });
    assert.equal(hostileStatus, 403);
    const deleted = await fetch(base + "/api/tasks/dashboard-watch", { method: "DELETE" });
    assert.equal((await deleted.json()).deleted, true);
  } finally {
    await dashboard.close();
  }
});

test("dashboard refuses non-loopback network binding", () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  assert.throws(() => createDashboardServer({ scheduler, host: "0.0.0.0" }), /loopback/);
});

test("dashboard token protects management endpoints", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  const dashboard = createDashboardServer({ scheduler, port: 0, token: "test-secret" });
  const address = await dashboard.listen();
  const base = `http://127.0.0.1:${address.port}`;
  try {
    const page = await fetch(base + "/");
    assert.equal(page.status, 200);
    const script = await fetch(base + "/app.js");
    assert.equal(script.status, 200);
    const denied = await fetch(base + "/api/tasks");
    assert.equal(denied.status, 401);
    const allowed = await fetch(base + "/api/tasks", {
      headers: { Authorization: "Bearer test-secret" }
    });
    assert.equal(allowed.status, 200);
  } finally {
    await dashboard.close();
  }
});

test("process lock prevents a second scheduler instance and releases cleanly", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-lock-"));
  const lockPath = join(directory, "scheduler.lock");
  try {
    const release = await acquireProcessLock(lockPath);
    await assert.rejects(() => acquireProcessLock(lockPath), /already appears to be running/);
    await release();
    const releaseAgain = await acquireProcessLock(lockPath);
    await releaseAgain();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("dashboard task changes are not lost while a scheduled handler is running", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  let signalStarted;
  let releaseHandler;
  const started = new Promise(resolve => { signalStarted = resolve; });
  const gate = new Promise(resolve => { releaseHandler = resolve; });
  scheduler.registerHandler("test.slow", async () => {
    signalStarted();
    await gate;
  });
  await scheduler.addTask({
    id: "slow-running",
    name: "Slow running task",
    handlerType: "test.slow",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const execution = scheduler.runDue();
  await started;
  const added = await scheduler.addTask({
    id: "added-during-run",
    name: "Added during run",
    handlerType: "test.slow",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:01:00Z" }
  });
  assert.equal(added.id, "added-during-run");
  releaseHandler();
  await execution;
  assert.ok((await scheduler.listTasks()).some(task => task.id === "added-during-run"));
});

test("CLI creates a one-shot task for an explicitly requested Builder run resume", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-cli-resume-"));
  const stateFile = join(directory, "scheduler.json");
  const schedulerDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const cliPath = join(schedulerDirectory, "src", "cli.js");
  const child = spawn(process.execPath, [
    cliPath, "resume-nine-builders", "resume-one", "Resume Builder run", "builder:run-123"
  ], {
    cwd: schedulerDirectory,
    env: { ...process.env, SCHEDULER_STATE_FILE: stateFile },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", chunk => { stderr += chunk; });
  try {
    const exitCode = await new Promise((resolveExit, rejectExit) => {
      child.once("error", rejectExit);
      child.once("close", code => resolveExit(code));
    });
    assert.equal(exitCode, 0, stderr);
    const state = JSON.parse(await readFile(stateFile, "utf8"));
    const task = state.tasks.find(item => item.id === "resume-one");
    assert.equal(task.schedule.kind, "once");
    assert.equal(task.payload.resumeRunId, "builder:run-123");
    assert.equal(task.enabled, true);
    assert.equal(typeof task.nextRunAt, "string");
  } finally {
    if (child.exitCode == null) child.kill("SIGKILL");
    await rm(directory, { recursive: true, force: true });
  }
});

test("service runner handles SIGTERM immediately at readiness and releases its lock", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-runner-"));
  const stateFile = join(directory, "state.json");
  const schedulerDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const child = spawn(process.execPath, ["src/runner.js"], {
    cwd: schedulerDirectory,
    env: {
      ...process.env,
      SCHEDULER_STATE_FILE: stateFile,
      SCHEDULER_DASHBOARD_PORT: "0",
      SCHEDULER_POLL_MS: "1000"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  let errorOutput = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", chunk => { output += chunk; });
  child.stderr.on("data", chunk => { errorOutput += chunk; });
  try {
    const startup = await new Promise((resolveStartup, rejectStartup) => {
      const timeout = setTimeout(() => rejectStartup(new Error("Scheduler runner did not start in time")), 8000);
      const inspect = () => {
        const line = output.split("\n").find(value => {
          try { return JSON.parse(value).event === "scheduler_started"; }
          catch { return false; }
        });
        if (!line) return;
        clearTimeout(timeout);
        resolveStartup(JSON.parse(line));
      };
      child.stdout.on("data", inspect);
      child.once("error", error => { clearTimeout(timeout); rejectStartup(error); });
      child.once("exit", code => {
        if (!lineExists(output)) {
          clearTimeout(timeout);
          rejectStartup(new Error(`Scheduler exited before startup (${code}): ${errorOutput}`));
        }
      });
      inspect();
    });
    assert.match(startup.dashboard, /^http:\/\/127\.0\.0\.1:\d+$/);
    // Deliberately signal immediately after scheduler_started. No health request
    // or extra event-loop turn is allowed to hide a late signal-handler install.
    child.kill("SIGTERM");
    const exitCode = await new Promise((resolveExit, rejectExit) => {
      const timeout = setTimeout(() => rejectExit(new Error("Scheduler runner did not shut down")), 8000);
      child.once("exit", code => { clearTimeout(timeout); resolveExit(code); });
    });
    assert.equal(exitCode, 0);
    assert.ok(output.split("\n").some(value => {
      try {
        const event = JSON.parse(value);
        return event.event === "scheduler_stopping" && event.signal === "SIGTERM";
      } catch { return false; }
    }), "SIGTERM shutdown handler must run at the readiness boundary");
    await assert.rejects(() => readFile(stateFile + ".lock", "utf8"), { code: "ENOENT" });
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    await rm(directory, { recursive: true, force: true });
  }
});

function lineExists(output) {
  return output.split("\n").some(value => {
    try { return JSON.parse(value).event === "scheduler_started"; }
    catch { return false; }
  });
}

test("abortActiveRuns propagates cooperative cancellation to the active handler", async () => {
  const scheduler = new Scheduler({ store: new MemoryStore(), clock });
  let handlerStarted;
  const started = new Promise(resolve => { handlerStarted = resolve; });
  let observedAbort;
  const aborted = new Promise(resolve => { observedAbort = resolve; });
  scheduler.registerHandler("test.cancellable", async (_payload, context) => {
    handlerStarted();
    if (context.signal.aborted) observedAbort(context.signal.reason);
    else context.signal.addEventListener("abort", () => observedAbort(context.signal.reason), { once: true });
    await aborted;
  });
  await scheduler.addTask({
    id: "cooperative-cancel",
    name: "Cooperative cancellation",
    handlerType: "test.cancellable",
    schedule: { kind: "interval", intervalMs: 60000, startAt: "2026-11-04T12:00:00Z" }
  });
  const execution = scheduler.runDue();
  await started;
  assert.equal(scheduler.abortActiveRuns("test shutdown"), 1);
  const reason = await aborted;
  assert.match(String(reason?.message || reason), /test shutdown/);
  const result = await execution;
  assert.equal(result.runs[0].status, "failed");
  assert.match(result.runs[0].error, /test shutdown/);
});
