import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { registerGithubWatch } from "../src/github-watch.js";
import { JsonFileStore, MemoryStore } from "../src/store.js";
import { Scheduler } from "../src/scheduler.js";

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
  }), /Schedule kind must be interval or calendar/);
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
    await second.runDue();
    assert.equal(output.filter(item => item.event === "github_build_failure").length, 1);
    assert.equal(output.find(item => item.event === "github_build_failure").runId, 11);
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
