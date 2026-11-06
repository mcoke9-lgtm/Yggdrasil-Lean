import { randomUUID } from "node:crypto";

export const MAX_BUILDER_RUN_DURATION_MS = 8 * 60 * 60 * 1000;
export const BUILDER_ROLES = Object.freeze([
  "ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR", "BALDR"
]);
export const BUILDER_ERROR_CODES = Object.freeze([
  "EXECUTOR_UNAVAILABLE", "EXECUTOR_FAILURE", "CONSENT_REQUIRED", "CONSENT_STALE",
  "TASK_VAULT_UNAVAILABLE", "VAULT_RESULT_MISSING", "VAULT_IDEMPOTENCY_CONFLICT",
  "PROVIDER_TIMEOUT", "PROVIDER_REJECTED", "PROVIDER_INVALID_RESPONSE",
  "EVIDENCE_UNAVAILABLE", "EVIDENCE_MISMATCH", "CHECKPOINT_INVALID",
  "RESUME_NOT_SAFE", "MANUAL_RECONCILIATION_REQUIRED", "CANCELLED",
  "DEADLINE_EXPIRED", "HOST_INTERRUPTED", "EXTERNAL_OUTCOME_UNKNOWN",
  "BUILDER_REQUESTED_INTERRUPTION", "INTERNAL_ERROR"
]);
const ERROR_CODES = new Set(BUILDER_ERROR_CODES);
const TURN_TYPES = new Set([
  "CONTRIBUTION", "QUESTION", "CHALLENGE", "RESPONSE", "HANDOFF",
  "ACKNOWLEDGEMENT", "RECONCILIATION", "INTERRUPTION",
  "IMPROVEMENT_INCIDENT", "CORRECTION_PLAN", "CORRECTION_UPDATE", "DECISION"
]);
const EVIDENCE_STATUSES = new Set([
  "PROPOSED", "OBSERVED", "IMPLEMENTED", "TESTED", "VERIFIED",
  "ADOPTED", "USER_CONFIRMED", "DISPUTED", "UNKNOWN"
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

function safeIdentifier(value, label) {
  if (typeof value !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(value)) {
    throw new TypeError(label + " must be a stable, safe identifier");
  }
  return value;
}

function safeErrorCode(value, label = "errorCode") {
  if (value == null) return null;
  if (typeof value !== "string" || !ERROR_CODES.has(value)) {
    throw new TypeError(label + " must be an allowlisted Builder error code");
  }
  return value;
}

function safeCheckpoint(value, recordedAt) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("Checkpoint metadata must be a typed object");
  }
  const allowed = ["checkpointId", "vaultTurnId", "responseSha256", "turnType", "evidenceStatus", "safeToResume"];
  if (Object.keys(value).some(key => !allowed.includes(key))) {
    throw new TypeError("Checkpoint metadata contains an unrecognized field");
  }
  const checkpointId = safeIdentifier(value.checkpointId, "checkpointId");
  const vaultTurnId = safeIdentifier(value.vaultTurnId ?? checkpointId, "vaultTurnId");
  const responseSha256 = value.responseSha256 ?? null;
  if (responseSha256 != null &&
      (typeof responseSha256 !== "string" || !/^[a-f0-9]{64}$/.test(responseSha256))) {
    throw new TypeError("responseSha256 must be lowercase SHA-256 hex");
  }
  const turnType = value.turnType ?? null;
  if (turnType != null && !TURN_TYPES.has(turnType)) throw new TypeError("turnType is not supported");
  const evidenceStatus = value.evidenceStatus ?? null;
  if (evidenceStatus != null && !EVIDENCE_STATUSES.has(evidenceStatus)) {
    throw new TypeError("evidenceStatus is not supported");
  }
  if (typeof value.safeToResume !== "boolean") throw new TypeError("safeToResume must be boolean");
  return {
    checkpointId, vaultTurnId, responseSha256, turnType, evidenceStatus,
    safeToResume: value.safeToResume, recordedAt
  };
}

/**
 * Version-2 Builder ledger: typed operational metadata only.
 * Version-1 records remain readable but are immutable legacy plaintext.
 * Full Builder turns and diagnostics belong in the encrypted task vault.
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

  async createRun({
    runId = this.idFactory(), scheduledFor, durationMs = MAX_BUILDER_RUN_DURATION_MS,
    taskReference = null, supersedesRunId = null, sourceHistorySha256 = null
  } = {}) {
    return this.withLock(async () => {
      safeIdentifier(runId, "runId");
      if (!Number.isSafeInteger(durationMs) || durationMs < 1000 || durationMs > MAX_BUILDER_RUN_DURATION_MS) {
        throw new RangeError("durationMs must be between 1 second and the eight-hour maximum");
      }
      if (taskReference != null &&
          (typeof taskReference !== "string" || !taskReference.trim() || taskReference.length > 256)) {
        throw new TypeError("taskReference must be a non-empty opaque reference no longer than 256 characters");
      }
      if (supersedesRunId != null) {
        safeIdentifier(supersedesRunId, "supersedesRunId");
        if (supersedesRunId === runId ||
            typeof sourceHistorySha256 !== "string" || !/^[a-f0-9]{64}$/.test(sourceHistorySha256)) {
          throw new TypeError("A continuation requires a distinct superseded run and source-history SHA-256");
        }
      } else if (sourceHistorySha256 != null) {
        throw new TypeError("sourceHistorySha256 requires supersedesRunId");
      }
      const created = this.clock().toISOString();
      const scheduled = iso(scheduledFor ?? created, "scheduledFor");
      const state = await this.store.load();
      state.builderRuns ??= [];
      if (state.builderRuns.some(run => run.runId === runId)) throw new Error("Builder run already exists: " + runId);
      const run = {
        schemaVersion: 2, runId, taskReference: taskReference == null ? null : taskReference.trim(),
        supersedesRunId, sourceHistorySha256,
        scheduledFor: scheduled, startedAt: null, deadlineAt: null,
        durationMs, status: "queued", createdAt: created, updatedAt: created,
        interruptedAt: null, interruptionCode: null, finishedAt: null,
        builders: BUILDER_ROLES.map(role => ({
          role, status: "pending", attempt: 0, startedAt: null, finishedAt: null,
          checkpoint: null, errorCode: null, outcomeRef: null,
          outcomeType: null, evidenceStatus: null
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
      run.interruptionCode = null;
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
      builder.errorCode = null;
    });
  }

  async checkpointBuilder(runId, role, details = {}) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      const builder = this.builder(run, role);
      if (builder.status !== "running") throw new Error("Only a running Builder can checkpoint");
      builder.checkpoint = safeCheckpoint(details, this.clock().toISOString());
    });
  }

  async recoverBuilder(runId, role, {
    checkpointId, responseSha256, turnType = null, evidenceStatus = null
  } = {}) {
    return this.mutate(runId, run => {
      this.assertRunning(run);
      const builder = this.builder(run, role);
      if (builder.status !== "interrupted") {
        throw new Error("Only an interrupted Builder can recover from a durable result");
      }
      const checkpoint = safeCheckpoint({
        checkpointId, vaultTurnId: checkpointId, responseSha256,
        turnType, evidenceStatus, safeToResume: true
      }, this.clock().toISOString());
      builder.status = "succeeded";
      builder.finishedAt = this.clock().toISOString();
      builder.errorCode = null;
      builder.outcomeRef = checkpoint.vaultTurnId;
      builder.outcomeType = checkpoint.turnType;
      builder.evidenceStatus = checkpoint.evidenceStatus;
      builder.checkpoint = checkpoint;
    });
  }

  async finishBuilder(runId, role, status, {
    errorCode = null, outcomeRef = null, outcomeType = null,
    evidenceStatus = null, responseSha256 = null
  } = {}) {
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
      const safeCode = safeErrorCode(errorCode);
      if (status === "failed" && safeCode == null) {
        throw new TypeError("A failed Builder requires an allowlisted errorCode");
      }
      let safeOutcomeRef = null;
      let safeOutcomeType = null;
      let safeEvidenceStatus = null;
      let safeHash = null;
      if (status === "succeeded") {
        safeOutcomeRef = safeIdentifier(outcomeRef, "outcomeRef");
        if (outcomeType != null && !TURN_TYPES.has(outcomeType)) throw new TypeError("outcomeType is not supported");
        if (evidenceStatus != null && !EVIDENCE_STATUSES.has(evidenceStatus)) throw new TypeError("evidenceStatus is not supported");
        if (responseSha256 != null && (typeof responseSha256 !== "string" || !/^[a-f0-9]{64}$/.test(responseSha256))) {
          throw new TypeError("responseSha256 must be lowercase SHA-256 hex");
        }
        safeOutcomeType = outcomeType;
        safeEvidenceStatus = evidenceStatus;
        safeHash = responseSha256;
      }
      builder.status = status;
      builder.finishedAt = this.clock().toISOString();
      builder.errorCode = status === "failed" ? safeCode : null;
      builder.outcomeRef = safeOutcomeRef;
      builder.outcomeType = safeOutcomeType;
      builder.evidenceStatus = safeEvidenceStatus;
      if (status === "succeeded") {
        builder.checkpoint = safeCheckpoint({
          checkpointId: safeOutcomeRef,
          vaultTurnId: safeOutcomeRef,
          responseSha256: safeHash,
          turnType: safeOutcomeType,
          evidenceStatus: safeEvidenceStatus,
          safeToResume: true
        }, builder.checkpoint?.recordedAt ?? this.clock().toISOString());
      }
    });
  }

  async interruptRun(runId, interruptionCode = "HOST_INTERRUPTED", activeRole = null) {
    return this.mutate(runId, run => {
      if (run.status !== "running") throw new Error("Only a running work window can be interrupted");
      const code = safeErrorCode(interruptionCode, "interruptionCode");
      if (code == null) throw new TypeError("interruptionCode is required");
      const now = this.clock().toISOString();
      run.status = "interrupted";
      run.interruptedAt = now;
      run.interruptionCode = code;
      for (const builder of run.builders) {
        if (builder.status === "running"
            || (builder.role === activeRole && !TERMINAL.has(builder.status))) {
          builder.status = "interrupted";
          builder.finishedAt = now;
          builder.errorCode = code;
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
      run.interruptionCode = "DEADLINE_EXPIRED";
      for (const builder of run.builders) {
        if (builder.status === "running" || builder.status === "pending") {
          builder.status = "interrupted";
          builder.finishedAt = now;
          builder.errorCode = "DEADLINE_EXPIRED";
        }
      }
    });
  }

  async recoverAbandonedRuns() {
    return this.withLock(async () => {
      const state = await this.store.load();
      state.builderRuns ??= [];
      const recovered = [];
      const now = this.clock();
      const nowIso = now.toISOString();
      for (const run of state.builderRuns) {
        if (run.schemaVersion !== 2 || run.status !== "running") continue;
        const expired = !run.deadlineAt || now.getTime() >= new Date(run.deadlineAt).getTime();
        const code = expired ? "DEADLINE_EXPIRED" : "HOST_INTERRUPTED";
        run.status = expired ? "expired" : "interrupted";
        run.interruptedAt = nowIso;
        run.updatedAt = nowIso;
        run.interruptionCode = code;
        if (expired) run.finishedAt = nowIso;
        for (const builder of run.builders) {
          if (builder.status === "running") {
            builder.status = "interrupted";
            builder.finishedAt = nowIso;
            builder.errorCode = code;
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
      if (run.schemaVersion !== 2) {
        throw new Error("Legacy Builder run is read-only; create a separate version-2 continuation after vault reconciliation");
      }
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
