import { randomUUID } from "node:crypto";

export const MAX_BUILDER_RUN_DURATION_MS = 8 * 60 * 60 * 1000;
export const BUILDER_ROLES = Object.freeze([
  "ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR", "BALDR"
]);
const TERMINAL = new Set(["succeeded", "failed", "skipped"]);

function iso(value, label) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new TypeError(label + " must be a valid timestamp");
  return date.toISOString();
}

function clone(value) {
  return structuredClone(value);
}

function safeCheckpoint(value) {
  const json = JSON.stringify(value ?? {});
  if (json === undefined || json.length > 16384) {
    throw new RangeError("Checkpoint metadata must be JSON and no larger than 16 KiB");
  }
  return JSON.parse(json);
}

/**
 * Durable metadata ledger for a single bounded Nine Builders work window.
 * It records checkpoints; it does not invoke models or claim that a host stays alive.
 */
export class BuilderRunLedger {
  constructor({ store, clock = () => new Date(), idFactory = randomUUID }) {
    if (!store || typeof store.load !== "function" || typeof store.save !== "function") {
      throw new TypeError("A compatible persistent store is required");
    }
    this.store = store;
    this.clock = clock;
    this.idFactory = idFactory;
    this.queue = Promise.resolve();
  }

  withLock(operation) {
    const result = this.queue.then(operation);
    this.queue = result.catch(() => {});
    return result;
  }

  async createRun({ runId = this.idFactory(), scheduledFor, durationMs = MAX_BUILDER_RUN_DURATION_MS } = {}) {
    return this.withLock(async () => {
      if (typeof runId !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(runId)) {
        throw new TypeError("runId must be a stable, safe identifier");
      }
      if (!Number.isSafeInteger(durationMs) || durationMs < 1000 || durationMs > MAX_BUILDER_RUN_DURATION_MS) {
        throw new RangeError("durationMs must be between 1 second and the eight-hour maximum");
      }
      const created = this.clock().toISOString();
      const scheduled = iso(scheduledFor ?? created, "scheduledFor");
      const state = await this.store.load();
      state.builderRuns ??= [];
      if (state.builderRuns.some(run => run.runId === runId)) throw new Error("Builder run already exists: " + runId);
      const run = {
        schemaVersion: 1, runId, scheduledFor: scheduled, startedAt: null,
        deadlineAt: null, durationMs, status: "queued", createdAt: created,
        updatedAt: created, interruptedAt: null, interruptionReason: null,
        builders: BUILDER_ROLES.map(role => ({
          role, status: "pending", attempt: 0, startedAt: null, finishedAt: null,
          checkpoint: null, error: null
        }))
      };
      state.builderRuns.push(run);
      await this.store.save(state);
      return clone(run);
    });
  }

  async getRun(runId) {
    return this.withLock(async () => {
      const state = await this.store.load();
      return clone((state.builderRuns ?? []).find(run => run.runId === runId) ?? null);
    });
  }

  async listRuns(limit = 50) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new RangeError("limit must be 1–500");
    return this.withLock(async () => {
      const state = await this.store.load();
      return clone((state.builderRuns ?? []).slice(-limit).reverse());
    });
  }

  async startRun(runId) {
    return this.mutate(runId, run => {
      if (run.status !== "queued" && run.status !== "interrupted") throw new Error("Only queued or interrupted runs can start");
      const now = this.clock();
      if (run.status === "queued") {
        run.startedAt = now.toISOString();
        run.deadlineAt = new Date(now.getTime() + run.durationMs).toISOString();
      } else if (!run.deadlineAt || now.getTime() >= new Date(run.deadlineAt).getTime()) {
        throw new Error("Run deadline has expired");
      }
      run.status = "running";
      run.interruptedAt = null;
      run.interruptionReason = null;
    });
  }

  async startBuilder(runId, role) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      const builder = this.builder(run, role);
      if (builder.status !== "pending" && builder.status !== "interrupted") throw new Error("Builder is not eligible to start");
      if (builder.status === "interrupted" && !builder.checkpoint?.safeToResume) {
        throw new Error("Interrupted Builder has no explicitly safe resume checkpoint");
      }
      if (role === "BALDR") {
        const unfinished = run.builders.filter(item => item.role !== "BALDR" && !TERMINAL.has(item.status));
        if (unfinished.length) throw new Error("Baldr review must wait until the other Builders have finished or been explicitly skipped");
      }
      builder.status = "running";
      builder.attempt += 1;
      builder.startedAt = this.clock().toISOString();
      builder.finishedAt = null;
      builder.error = null;
    });
  }

  async checkpointBuilder(runId, role, { checkpointId, data = {}, safeToResume = false } = {}) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      const builder = this.builder(run, role);
      if (builder.status !== "running") throw new Error("Only a running Builder can checkpoint");
      if (typeof checkpointId !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(checkpointId)) {
        throw new TypeError("checkpointId must be a stable, safe identifier");
      }
      if (typeof safeToResume !== "boolean") throw new TypeError("safeToResume must be boolean");
      builder.checkpoint = {
        checkpointId, data: safeCheckpoint(data), safeToResume,
        recordedAt: this.clock().toISOString()
      };
    });
  }

  async finishBuilder(runId, role, status, error = null) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      if (status !== "succeeded" && status !== "failed" && status !== "skipped") {
        throw new TypeError("Builder result must be succeeded, failed, or skipped");
      }
      if (role === "BALDR" && status === "skipped") {
        throw new Error("Baldr review cannot be silently skipped; record a failure if review is unavailable");
      }
      const builder = this.builder(run, role);
      if (builder.status !== "running" && !(status === "skipped" && builder.status === "pending")) {
        throw new Error("Builder is not in a finishable state");
      }
      builder.status = status;
      builder.finishedAt = this.clock().toISOString();
      builder.error = error == null ? null : String(error).slice(0, 2000);
    });
  }

  async interruptRun(runId, reason, activeRole = null) {
    return this.mutate(runId, run => {
      if (run.status !== "running") throw new Error("Only a running work window can be interrupted");
      const now = this.clock().toISOString();
      run.status = "interrupted";
      run.interruptedAt = now;
      run.interruptionReason = String(reason || "unspecified interruption").slice(0, 1000);
      for (const builder of run.builders) {
        if (builder.status === "running"
            || (builder.role === activeRole && !TERMINAL.has(builder.status))) {
          builder.status = "interrupted";
          builder.finishedAt = now;
          builder.error = "Interrupted; resume requires an explicitly safe checkpoint.";
        }
      }
    });
  }

  async completeRun(runId) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      if (run.builders.some(builder => !TERMINAL.has(builder.status))) {
        throw new Error("Cannot complete while any Builder is pending, running, or interrupted");
      }
      run.status = run.builders.some(builder => builder.status === "failed") ? "completed_with_failures" : "completed";
      run.finishedAt = this.clock().toISOString();
    });
  }

  async markDeadlineExpired(runId) {
    return this.mutate(runId, run => {
      if (run.status !== "running") throw new Error("Only a running work window can expire");
      if (!run.deadlineAt || this.clock().getTime() < new Date(run.deadlineAt).getTime()) throw new Error("Run deadline has not been reached");
      const now = this.clock().toISOString();
      run.status = "expired";
      run.finishedAt = now;
      run.interruptionReason = "Eight-hour maximum work window reached";
      for (const builder of run.builders) {
        if (builder.status === "running" || builder.status === "pending") {
          builder.status = "interrupted";
          builder.finishedAt = now;
          builder.error = "Work window expired; inspect checkpoint before resuming.";
        }
      }
    });
  }

  async recoverAbandonedRuns(reason = "Host restarted before the run outcome was recorded") {
    return this.withLock(async () => {
      const state = await this.store.load();
      state.builderRuns ??= [];
      const recovered = [];
      const now = this.clock();
      const nowIso = now.toISOString();
      for (const run of state.builderRuns) {
        if (run.status !== "running") continue;
        const expired = !run.deadlineAt || now.getTime() >= new Date(run.deadlineAt).getTime();
        run.status = expired ? "expired" : "interrupted";
        run.interruptedAt = nowIso;
        run.updatedAt = nowIso;
        run.interruptionReason = expired
          ? "Eight-hour maximum work window elapsed before recovery"
          : String(reason).slice(0, 1000);
        if (expired) run.finishedAt = nowIso;
        for (const builder of run.builders) {
          if (builder.status === "running") {
            builder.status = "interrupted";
            builder.finishedAt = nowIso;
            builder.error = "Host interruption; inspect checkpoint before any retry.";
          }
        }
        recovered.push(run.runId);
      }
      if (recovered.length) await this.store.save(state);
      return recovered;
    });
  }

  async mutate(runId, change) {
    return this.withLock(async () => {
      const state = await this.store.load();
      state.builderRuns ??= [];
      const run = state.builderRuns.find(item => item.runId === runId);
      if (!run) throw new Error("Builder run not found: " + runId);
      change(run);
      run.updatedAt = this.clock().toISOString();
      await this.store.save(state);
      return clone(run);
    });
  }

  assertRunning(run) {
    if (run.status !== "running") throw new Error("Builder run is not running");
    if (!run.deadlineAt || this.clock().getTime() >= new Date(run.deadlineAt).getTime()) {
      throw new Error("Run deadline has expired; stop work and mark the deadline");
    }
  }

  builder(run, role) {
    const builder = run.builders.find(item => item.role === role);
    if (!builder) throw new Error("Unknown Builder role: " + role);
    return builder;
  }
}
