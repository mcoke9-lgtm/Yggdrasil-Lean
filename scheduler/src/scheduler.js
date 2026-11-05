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
  }

  registerHandler(type, handler) {
    if (!/^[a-z][a-z0-9._-]{0,63}$/.test(type) || typeof handler !== "function") {
      throw new TypeError("Register a named handler function using a lowercase handler type");
    }
    if (this.handlers.has(type)) throw new Error(`Handler already registered: ${type}`);
    this.handlers.set(type, handler);
  }

  async addTask(input) {
    if (!input || !ID_PATTERN.test(input.id || "")) throw new TypeError("Task id must be 1–80 safe characters");
    if (typeof input.name !== "string" || !input.name.trim()) throw new TypeError("Task name is required");
    if (!/^[a-z][a-z0-9._-]{0,63}$/.test(input.handlerType || "")) throw new TypeError("A registered handlerType is required");
    if (!input.schedule || input.schedule.kind !== "interval") {
      throw new TypeError("Only interval schedules are supported in prototype 0.1");
    }
    const intervalMs = input.schedule.intervalMs;
    if (!Number.isSafeInteger(intervalMs) || intervalMs < 1000 || intervalMs > MAX_INTERVAL_MS) {
      throw new RangeError("intervalMs must be between 1 second and 366 days");
    }
    const startAt = validDate(input.schedule.startAt ?? this.clock(), "schedule.startAt");
    const createdAt = this.clock().toISOString();
    const task = {
      id: input.id,
      name: input.name.trim(),
      enabled: input.enabled !== false,
      handlerType: input.handlerType,
      payload: clone(input.payload ?? {}),
      schedule: { kind: "interval", intervalMs, startAt },
      nextRunAt: startAt,
      createdAt,
      updatedAt: createdAt
    };
    const state = await this.store.load();
    if (state.tasks.some(existing => existing.id === task.id)) throw new Error(`Task already exists: ${task.id}`);
    state.tasks.push(task);
    await this.store.save(state);
    return clone(task);
  }

  async setEnabled(taskId, enabled) {
    if (typeof enabled !== "boolean") throw new TypeError("enabled must be boolean");
    const state = await this.store.load();
    const task = state.tasks.find(item => item.id === taskId);
    if (!task) throw new Error(`Task not found: ${taskId}`);
    task.enabled = enabled;
    task.updatedAt = this.clock().toISOString();
    await this.store.save(state);
    return clone(task);
  }

  async listTasks() {
    const state = await this.store.load();
    return clone(state.tasks);
  }

  async listRuns({ taskId, limit = 50 } = {}) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new RangeError("limit must be 1–500");
    const state = await this.store.load();
    return clone(state.runs.filter(run => !taskId || run.taskId === taskId).slice(-limit).reverse());
  }

  async runDue() {
    if (this.tickInProgress) return { skipped: true, reason: "tick_already_running", runs: [] };
    this.tickInProgress = true;
    try {
      const now = this.clock();
      const nowIso = now.toISOString();
      const state = await this.store.load();

      // A process restart must not silently replay a handler whose outcome is unknown.
      for (const run of state.runs) {
        if (run.status === "running") {
          run.status = "interrupted";
          run.finishedAt = nowIso;
          run.error = "Process stopped before the handler outcome was recorded; automatic replay is disabled.";
        }
      }

      const due = state.tasks
        .filter(task => task.enabled && new Date(task.nextRunAt).getTime() <= now.getTime())
        .sort((a, b) => a.nextRunAt.localeCompare(b.nextRunAt))
        .slice(0, this.maxRunsPerTick);

      const started = [];
      for (const task of due) {
        const scheduledFor = task.nextRunAt;
        const runId = `${task.id}:${scheduledFor}`;
        if (state.runs.some(run => run.id === runId)) {
          task.nextRunAt = new Date(new Date(scheduledFor).getTime() + task.schedule.intervalMs).toISOString();
          task.updatedAt = nowIso;
          continue;
        }

        const run = {
          id: runId,
          taskId: task.id,
          handlerType: task.handlerType,
          scheduledFor,
          startedAt: nowIso,
          finishedAt: null,
          status: "running",
          error: null
        };
        state.runs.push(run);
        task.nextRunAt = new Date(new Date(scheduledFor).getTime() + task.schedule.intervalMs).toISOString();
        task.updatedAt = nowIso;
        await this.store.save(state);
        started.push(runId);

        const handler = this.handlers.get(task.handlerType);
        try {
          if (!handler) throw new Error(`No handler registered for type: ${task.handlerType}`);
          await handler(clone(task.payload), {
            taskId: task.id,
            runId,
            scheduledFor,
            signal: undefined
          });
          run.status = "succeeded";
        } catch (error) {
          run.status = "failed";
          run.error = String(error?.message || error).slice(0, 2000);
        }
        run.finishedAt = this.clock().toISOString();
        await this.store.save(state);
      }

      return {
        skipped: false,
        considered: due.length,
        started,
        runs: state.runs.filter(run => started.includes(run.id)).map(clone)
      };
    } finally {
      this.tickInProgress = false;
    }
  }
}
