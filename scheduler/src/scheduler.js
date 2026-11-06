import { nextCalendarOccurrence, validateCalendarSchedule } from "./calendar.js";

const MAX_INTERVAL_MS = 366 * 24 * 60 * 60 * 1000;
const ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$/;

function validDate(value, label) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new TypeError(`${label} must be a valid date/time`);
  return date.toISOString();
}

function clone(value) {
  return structuredClone(value);
}

export class Scheduler {
  constructor({ store, clock = () => new Date(), maxRunsPerTick = 10 }) {
    if (!store || typeof store.load !== "function" || typeof store.save !== "function") {
      throw new TypeError("A compatible persistent store is required");
    }
    if (!Number.isInteger(maxRunsPerTick) || maxRunsPerTick < 1 || maxRunsPerTick > 100) {
      throw new RangeError("maxRunsPerTick must be an integer from 1 to 100");
    }
    this.store = store;
    this.clock = clock;
    this.maxRunsPerTick = maxRunsPerTick;
    this.handlers = new Map();
    this.tickInProgress = false;
    this.stateQueue = Promise.resolve();
    this.activeControllers = new Set();
  }

  withStateLock(operation) {
    const result = this.stateQueue.then(operation);
    this.stateQueue = result.catch(() => {});
    return result;
  }

  registerHandler(type, handler) {
    if (!/^[a-z][a-z0-9._-]{0,63}$/.test(type) || typeof handler !== "function") {
      throw new TypeError("Register a named handler function using a lowercase handler type");
    }
    if (this.handlers.has(type)) throw new Error(`Handler already registered: ${type}`);
    this.handlers.set(type, handler);
  }

  abortActiveRuns(reason = "Scheduler shutdown requested") {
    for (const controller of this.activeControllers) {
      if (!controller.signal.aborted) controller.abort(new Error(String(reason)));
    }
    return this.activeControllers.size;
  }

  async addTask(input) {
    return this.withStateLock(async () => {
      if (!input || !ID_PATTERN.test(input.id || "")) throw new TypeError("Task id must be 1–80 safe characters");
      if (typeof input.name !== "string" || !input.name.trim()) throw new TypeError("Task name is required");
      if (!/^[a-z][a-z0-9._-]{0,63}$/.test(input.handlerType || "")) throw new TypeError("A registered handlerType is required");
      let schedule;
      let nextRunAt;
      if (input.schedule?.kind === "interval") {
        const intervalMs = input.schedule.intervalMs;
        if (!Number.isSafeInteger(intervalMs) || intervalMs < 1000 || intervalMs > MAX_INTERVAL_MS) {
          throw new RangeError("intervalMs must be between 1 second and 366 days");
        }
        const startAt = validDate(input.schedule.startAt ?? this.clock(), "schedule.startAt");
        schedule = { kind: "interval", intervalMs, startAt };
        nextRunAt = startAt;
      } else if (input.schedule?.kind === "calendar") {
        schedule = validateCalendarSchedule(input.schedule);
        const reference = validDate(input.schedule.startAt ?? this.clock(), "schedule.startAt");
        nextRunAt = nextCalendarOccurrence(schedule, reference);
      } else if (input.schedule?.kind === "once") {
        const startAt = validDate(input.schedule.startAt ?? this.clock(), "schedule.startAt");
        schedule = { kind: "once", startAt };
        nextRunAt = startAt;
      } else {
        throw new TypeError("Schedule kind must be interval, calendar, or once");
      }
      const createdAt = this.clock().toISOString();
      const task = {
        id: input.id,
        name: input.name.trim(),
        enabled: input.enabled !== false,
        handlerType: input.handlerType,
        payload: clone(input.payload ?? {}),
        schedule,
        nextRunAt,
        ...(schedule.kind === "once" ? { oneShotStatus: "pending" } : {}),
        createdAt,
        updatedAt: createdAt
      };
      const state = await this.store.load();
      if (state.tasks.some(existing => existing.id === task.id)) throw new Error(`Task already exists: ${task.id}`);
      state.tasks.push(task);
      await this.store.save(state);
      return clone(task);
    });
  }

  async setEnabled(taskId, enabled) {
    return this.withStateLock(async () => {
      if (typeof enabled !== "boolean") throw new TypeError("enabled must be boolean");
      const state = await this.store.load();
      const task = state.tasks.find(item => item.id === taskId);
      if (!task) throw new Error(`Task not found: ${taskId}`);
      if (enabled && task.schedule.kind === "once" && task.nextRunAt == null) {
        throw new Error("A completed one-time task cannot be re-enabled; create a new task for another run");
      }
      task.enabled = enabled;
      task.updatedAt = this.clock().toISOString();
      await this.store.save(state);
      return clone(task);
    });
  }

  async deleteTask(taskId) {
    return this.withStateLock(async () => {
      const state = await this.store.load();
      const task = state.tasks.find(item => item.id === taskId);
      if (!task) throw new Error(`Task not found: ${taskId}`);
      if (state.runs.some(run => run.taskId === taskId && run.status === "running")) {
        throw new Error("Cannot delete a task while its handler is running");
      }
      state.tasks = state.tasks.filter(item => item.id !== taskId);
      await this.store.save(state);
      return clone(task);
    });
  }

  async listTasks() {
    return this.withStateLock(async () => clone((await this.store.load()).tasks));
  }

  async listRuns({ taskId, limit = 50 } = {}) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new RangeError("limit must be 1–500");
    return this.withStateLock(async () => {
      const state = await this.store.load();
      return clone(state.runs.filter(run => !taskId || run.taskId === taskId).slice(-limit).reverse());
    });
  }

  nextOccurrence(task, scheduledFor) {
    if (task.schedule.kind === "once") return null;
    if (task.schedule.kind === "interval") {
      return new Date(new Date(scheduledFor).getTime() + task.schedule.intervalMs).toISOString();
    }
    return nextCalendarOccurrence(task.schedule, scheduledFor);
  }

  async runDue() {
    if (this.tickInProgress) return { skipped: true, reason: "tick_already_running", runs: [] };
    this.tickInProgress = true;
    try {
      const now = this.clock();
      const nowIso = now.toISOString();
      const due = await this.withStateLock(async () => {
        const state = await this.store.load();
        let recovered = false;
        for (const run of state.runs) {
          if (run.status === "running") {
            run.status = "interrupted";
            run.finishedAt = nowIso;
            run.error = "Process stopped before the handler outcome was recorded; automatic replay is disabled.";
            const task = state.tasks.find(item => item.id === run.taskId);
            if (task?.schedule?.kind === "once") {
              task.enabled = false;
              task.nextRunAt = null;
              task.oneShotStatus = "interrupted";
              task.updatedAt = nowIso;
            }
            recovered = true;
          }
        }
        if (recovered) await this.store.save(state);
        return state.tasks
          .filter(task => task.enabled && typeof task.nextRunAt === "string" &&
            new Date(task.nextRunAt).getTime() <= now.getTime())
          .sort((a, b) => a.nextRunAt.localeCompare(b.nextRunAt))
          .slice(0, this.maxRunsPerTick)
          .map(task => ({ id: task.id, scheduledFor: task.nextRunAt }));
      });

      const completedRuns = [];
      const started = [];
      for (const candidate of due) {
        const reservation = await this.withStateLock(async () => {
          const state = await this.store.load();
          const task = state.tasks.find(item => item.id === candidate.id);
          if (!task || !task.enabled || task.nextRunAt !== candidate.scheduledFor) return null;
          const scheduledFor = task.nextRunAt;
          const runId = `${task.id}:${scheduledFor}`;
          if (state.runs.some(run => run.id === runId)) {
            task.nextRunAt = this.nextOccurrence(task, scheduledFor);
            if (task.schedule.kind === "once") {
              task.enabled = false;
              task.oneShotStatus = "already-run";
            }
            task.updatedAt = this.clock().toISOString();
            await this.store.save(state);
            return null;
          }
          const run = {
            id: runId,
            taskId: task.id,
            handlerType: task.handlerType,
            scheduledFor,
            startedAt: this.clock().toISOString(),
            finishedAt: null,
            status: "running",
            error: null
          };
          state.runs.push(run);
          task.nextRunAt = this.nextOccurrence(task, scheduledFor);
          if (task.schedule.kind === "once") {
            task.enabled = false;
            task.oneShotStatus = "running";
          }
          task.updatedAt = this.clock().toISOString();
          await this.store.save(state);
          return { run: clone(run), payload: clone(task.payload) };
        });
        if (!reservation) continue;

        const { run, payload } = reservation;
        started.push(run.id);
        const handler = this.handlers.get(run.handlerType);
        let status = "succeeded";
        let oneShotOutcome = "succeeded";
        let errorMessage = null;
        const controller = new AbortController();
        this.activeControllers.add(controller);
        try {
          if (!handler) throw new Error(`No handler registered for type: ${run.handlerType}`);
          await handler(payload, {
            taskId: run.taskId,
            runId: run.id,
            scheduledFor: run.scheduledFor,
            signal: controller.signal
          });
        } catch (error) {
          status = "failed";
          oneShotOutcome = controller.signal.aborted || error?.name === "BuilderRequestedInterruption"
            ? "interrupted"
            : "failed";
          errorMessage = String(error?.message || error).slice(0, 2000);
        } finally {
          this.activeControllers.delete(controller);
        }

        const finished = await this.withStateLock(async () => {
          const state = await this.store.load();
          const storedRun = state.runs.find(item => item.id === run.id);
          if (!storedRun) throw new Error(`Execution record disappeared: ${run.id}`);
          if (storedRun.status === "running") {
            storedRun.status = status;
            storedRun.error = errorMessage;
            storedRun.finishedAt = this.clock().toISOString();
            const task = state.tasks.find(item => item.id === storedRun.taskId);
            if (task?.schedule?.kind === "once") {
              task.enabled = false;
              task.nextRunAt = null;
              task.oneShotStatus = oneShotOutcome;
              task.updatedAt = storedRun.finishedAt;
            }
            await this.store.save(state);
          }
          return clone(storedRun);
        });
        completedRuns.push(finished);
      }

      return { skipped: false, considered: due.length, started, runs: completedRuns };
    } finally {
      this.tickInProgress = false;
    }
  }
}
