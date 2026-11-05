import test from "node:test";
import assert from "node:assert/strict";
import { MemoryStore } from "../src/store.js";
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
    schedule: { kind: "calendar" }
  }), /Only interval schedules/);
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
