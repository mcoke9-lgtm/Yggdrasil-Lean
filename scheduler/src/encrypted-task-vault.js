import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import { mkdir, open, readFile, rename, lstat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { validateBuilderHistory, validateBuilderReplyAgainstHistory } from "./builder-turn-history.js";
import { parseBuilderEvidenceReference, verifyBuilderEvidenceReference } from "./builder-evidence-reference.js";
import { verifyUserAdoptionAuthorization } from "./user-adoption-authorization.js";
import { acquireProcessLock } from "./process-lock.js";

const MAGIC = "YGGDRASIL-BUILDER-VAULT";
const VERSION = 1;
const MAX_VAULT_BYTES = 8 * 1024 * 1024;
const MUTATION_LOCK_MAX_WAIT_MS = 30000;
const mutationQueues = new Map();

function required(value, label, max = 4096) {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new TypeError(label + " must be a non-empty string no longer than " + max + " characters");
  }
  return value.trim();
}

function normalizedReply(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError("Builder result reply envelope is required");
  }
  return {
    body: required(value.body, "Builder result body", 8000),
    turn_type: required(value.turn_type, "Builder result turn_type", 64),
    evidence_status: required(value.evidence_status, "Builder result evidence_status", 64),
    recipient: value.recipient == null ? null : required(value.recipient, "Builder result recipient", 32),
    reply_to_turn_id: value.reply_to_turn_id == null
      ? null : required(value.reply_to_turn_id, "Builder result reply_to_turn_id", 256)
  };
}

function historyManifestSha256(taskReference, runId, threadId, task, history) {
  const manifest = {
    taskReference,
    runId,
    threadId,
    promptTurnId: task.promptTurnId ?? null,
    promptCreatedAt: task.promptCreatedAt ?? null,
    history
  };
  return createHash("sha256").update(JSON.stringify(manifest), "utf8").digest("hex");
}

function keyFromEnvironment() {
  const encoded = required(process.env.BUILDER_TASK_VAULT_KEY, "BUILDER_TASK_VAULT_KEY", 256);
  const key = Buffer.from(encoded, "base64");
  if (key.length !== 32 || key.toString("base64").replace(/=+$/, "") !== encoded.replace(/=+$/, "")) {
    throw new Error("BUILDER_TASK_VAULT_KEY must be a base64-encoded 32-byte key");
  }
  return key;
}

function encrypt(state, key) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(MAGIC + ":" + VERSION));
  const plaintext = Buffer.from(JSON.stringify(state), "utf8");
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([
    Buffer.from(MAGIC + "\n" + VERSION + "\n", "utf8"),
    iv, cipher.getAuthTag(), ciphertext
  ]);
}

function decrypt(data, key) {
  const header = Buffer.from(MAGIC + "\n" + VERSION + "\n", "utf8");
  if (data.length < header.length + 28 || !data.subarray(0, header.length).equals(header)) {
    throw new Error("Task vault format or version is invalid");
  }
  const iv = data.subarray(header.length, header.length + 12);
  const tag = data.subarray(header.length + 12, header.length + 28);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAAD(Buffer.from(MAGIC + ":" + VERSION));
  decipher.setAuthTag(tag);
  const plain = Buffer.concat([
    decipher.update(data.subarray(header.length + 28)), decipher.final()
  ]).toString("utf8");
  const state = JSON.parse(plain);
  if (!state || state.version !== VERSION || !state.tasks || typeof state.tasks !== "object") {
    throw new Error("Decrypted task vault schema is invalid");
  }
  return state;
}

export function createEncryptedTaskVault({
  filePath = process.env.BUILDER_TASK_VAULT_FILE,
  key = keyFromEnvironment(),
  userAuthorizationPublicKeys = {},
  userAuthorizationMaxAgeMs = 300000,
  evidenceGateway = null,
  evidenceTimeoutMs = 15000,
  clock = () => new Date()
} = {}) {
  const path = resolve(required(filePath, "BUILDER_TASK_VAULT_FILE", 4096));
  if (!Buffer.isBuffer(key) || key.length !== 32) throw new TypeError("Task vault key must be exactly 32 bytes");
  if (!userAuthorizationPublicKeys || typeof userAuthorizationPublicKeys !== "object" ||
      Array.isArray(userAuthorizationPublicKeys)) {
    throw new TypeError("User authorization public keys must be a key-ID map");
  }
  if (!Number.isSafeInteger(userAuthorizationMaxAgeMs) ||
      userAuthorizationMaxAgeMs < 1000 || userAuthorizationMaxAgeMs > 900000) {
    throw new RangeError("User authorization max age must be between 1 second and 15 minutes");
  }
  const authorizationKeys = Object.freeze({ ...userAuthorizationPublicKeys });
  if (evidenceGateway != null && (typeof evidenceGateway !== "object" || Array.isArray(evidenceGateway))) {
    throw new TypeError("Evidence gateway must be a read-only gateway object");
  }
  if (!Number.isSafeInteger(evidenceTimeoutMs) || evidenceTimeoutMs < 1000 || evidenceTimeoutMs > 300000) {
    throw new RangeError("Evidence timeout must be between 1 second and 5 minutes");
  }
  async function acquireMutationLock() {
    const lockPath = path + ".mutation.lock";
    const deadline = Date.now() + MUTATION_LOCK_MAX_WAIT_MS;
    let delayMs = 10;
    while (true) {
      try {
        return await acquireProcessLock(lockPath);
      } catch (error) {
        const message = String(error?.message || error);
        const contention = /already appears to be running as PID|stale-lock recovery is already in progress|Could not acquire scheduler process lock/.test(message);
        if (!contention || Date.now() >= deadline) {
          throw new Error("Encrypted task vault mutation lock unavailable; no mutation was committed: " + message);
        }
        await new Promise(resolveDelay => setTimeout(resolveDelay, delayMs));
        delayMs = Math.min(250, Math.ceil(delayMs * 1.5));
      }
    }
  }

  function serializeMutation(operation) {
    const prior = mutationQueues.get(path) ?? Promise.resolve();
    const result = prior.then(async () => {
      const release = await acquireMutationLock();
      try {
        return await operation();
      } finally {
        await release();
      }
    });
    const settled = result.catch(() => {});
    mutationQueues.set(path, settled);
    void settled.then(() => {
      if (mutationQueues.get(path) === settled) mutationQueues.delete(path);
    });
    return result;
  }

  async function readState() {
    try {
      const info = await lstat(path);
      if (!info.isFile() || info.isSymbolicLink()) throw new Error("Task vault must be a regular non-symlink file");
      if (process.platform !== "win32" && (info.mode & 0o077) !== 0) {
        throw new Error("Task vault permissions are too broad; use owner-only permissions (0600)");
      }
      if (info.size > MAX_VAULT_BYTES) throw new RangeError("Task vault exceeds 8 MiB");
      return decrypt(await readFile(path), key);
    } catch (error) {
      if (error.code === "ENOENT") return { version: VERSION, tasks: {} };
      throw error;
    }
  }

  async function writeState(state) {
    const directory = dirname(path);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    if (process.platform !== "win32") {
      const dirInfo = await lstat(directory);
      if ((dirInfo.mode & 0o077) !== 0) throw new Error("Task vault directory permissions are too broad; use 0700");
    }
    const bytes = encrypt(state, key);
    if (bytes.length > MAX_VAULT_BYTES) throw new RangeError("Encrypted task vault exceeds 8 MiB");
    const temp = path + ".tmp-" + randomBytes(8).toString("hex");
    const handle = await open(temp, "wx", 0o600);
    try {
      await handle.writeFile(bytes);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temp, path);
    if (process.platform !== "win32") {
      const handle = await open(directory, "r");
      try { await handle.sync(); } finally { await handle.close(); }
    }
  }

  async function persistOperationalConstraint(taskReference, entry, runtimeEvaluation, evidenceVerification = null) {
    return serializeMutation(async () => {
      const reference = required(taskReference, "taskReference", 256);
      if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
        throw new TypeError("Operational constraint entry is required");
      }
      const constraintId = required(entry.constraint_id, "constraint_id", 256);
      if (!runtimeEvaluation || typeof runtimeEvaluation !== "object" ||
          Array.isArray(runtimeEvaluation)) {
        throw new TypeError("Operational constraint runtime evaluation is required");
      }
      const evaluatedOn = required(runtimeEvaluation.evaluated_on, "evaluated_on", 10);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(evaluatedOn) ||
          !Number.isFinite(Date.parse(evaluatedOn + "T00:00:00.000Z")) ||
          new Date(evaluatedOn + "T00:00:00.000Z").toISOString().slice(0, 10) !== evaluatedOn) {
        throw new TypeError("Operational constraint evaluated_on must be a valid YYYY-MM-DD date");
      }
      const maximumAge = runtimeEvaluation.maximum_evidence_age_days;
      if (!Number.isFinite(maximumAge) || maximumAge < 0 ||
          runtimeEvaluation.live_telemetry !== false ||
          runtimeEvaluation.unlisted_capability_decision !== "RECHECK_REQUIRED") {
        throw new TypeError("Operational constraint runtime evaluation is invalid");
      }
      const storedEntry = JSON.parse(JSON.stringify(entry));
      if (evidenceVerification) storedEntry.evidenceVerification = evidenceVerification;
      if (storedEntry.constraint_id !== constraintId) {
        throw new TypeError("Operational constraint ID must be a trimmed stable string");
      }
      const state = await readState();
      const task = state.tasks[reference];
      if (!task) throw new Error("Task reference does not exist");
      const register = task.operationalConstraintRegister ??= {
        schema: "yggdrasil.operational-capacity-register",
        schema_version: "1.0.0",
        constraints: [],
        runtime_evaluation: {
          evaluated_on: evaluatedOn,
          maximum_evidence_age_days: maximumAge,
          live_telemetry: false,
          unlisted_capability_decision: "RECHECK_REQUIRED"
        }
      };
      if (register.schema !== "yggdrasil.operational-capacity-register" ||
          register.schema_version !== "1.0.0" || !Array.isArray(register.constraints)) {
        throw new Error("Stored operational constraint register is malformed");
      }
      const prior = register.constraints.find(item => item?.constraint_id === constraintId);
      if (prior) {
        const priorStable = { ...prior };
        const incomingStable = { ...storedEntry };
        delete priorStable.evidenceVerification;
        delete incomingStable.evidenceVerification;
        if (JSON.stringify(priorStable) !== JSON.stringify(incomingStable)) {
          throw new Error("Operational constraint ID already exists with different content; append a new ID instead");
        }
        return { durable: true, duplicate: true, constraintId, count: register.constraints.length };
      }
      register.constraints.push(storedEntry);
      register.runtime_evaluation = {
        evaluated_on: evaluatedOn,
        maximum_evidence_age_days: maximumAge,
        live_telemetry: false,
        unlisted_capability_decision: "RECHECK_REQUIRED"
      };
      await writeState(state);
      return { durable: true, duplicate: false, constraintId, count: register.constraints.length };
    });
  }

  return Object.freeze({
    async initializeTask(taskReference, prompt) {
      return serializeMutation(async () => {
      const reference = required(taskReference, "taskReference", 256);
      const taskPrompt = required(prompt, "task prompt", 32000);
      const state = await readState();
      if (state.tasks[reference]) {
        if (state.tasks[reference].prompt !== taskPrompt) {
          throw new Error("Task reference already exists with a different prompt; use a new reference");
        }
        return {
          taskReference: reference,
          approvalRequired: !state.tasks[reference].consent
        };
      }
      state.tasks[reference] = {
        prompt: taskPrompt,
        promptTurnId: "task-prompt-" + randomBytes(16).toString("hex"),
        promptCreatedAt: new Date().toISOString(),
        consent: null,
        runs: {}
      };
      await writeState(state);
      return { taskReference: reference, approvalRequired: true };
      });
    },

    async approveTask(taskReference, fingerprint, approvedAt = new Date().toISOString()) {
      return serializeMutation(async () => {
      const reference = required(taskReference, "taskReference", 256);
      const digest = required(fingerprint, "consent fingerprint", 64);
      if (!/^[a-f0-9]{64}$/.test(digest)) throw new TypeError("Consent fingerprint must be SHA-256 hex");
      const state = await readState();
      const task = state.tasks[reference];
      if (!task) throw new Error("Task reference does not exist");
      task.consent = {
        status: "USER_APPROVED",
        scope: "NINE_BUILDERS_OVERNIGHT",
        approvedAt: new Date(approvedAt).toISOString(),
        fingerprint: digest
      };
      await writeState(state);
      return { approved: true, taskReference: reference, approvedAt: task.consent.approvedAt };
      });
    },

    async getRunHistoryManifest(taskReference, { runId } = {}) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        const id = required(runId, "runId", 120);
        const state = await readState();
        const task = state.tasks[reference];
        if (!task) throw new Error("Task reference does not exist");
        const run = task.runs[id];
        if (!run || typeof run.threadId !== "string" || !Array.isArray(run.history)) {
          throw new Error("Source run history does not exist or is malformed");
        }
        if (typeof task.promptTurnId !== "string" || !task.promptTurnId.trim()) {
          throw new Error("Task prompt identity is unavailable for a history fork");
        }
        validateBuilderHistory(run.history, id, run.threadId, task.promptTurnId,
          task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
        if (run.history.some(turn => Object.hasOwn(turn, "origin"))) {
          throw new Error("Nested imported histories cannot be used as a v1 source; manual reconciliation required");
        }
        return {
          runId: id,
          threadId: run.threadId,
          historySha256: historyManifestSha256(reference, id, run.threadId, task, run.history),
          turnCount: run.history.length
        };
      });
    },

    async forkRunHistory(taskReference, {
      sourceRunId,
      targetRunId,
      expectedSourceThreadId,
      expectedSourceHistorySha256
    } = {}) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        const sourceId = required(sourceRunId, "sourceRunId", 120);
        const targetId = required(targetRunId, "targetRunId", 120);
        const expectedThread = required(expectedSourceThreadId, "expectedSourceThreadId", 256);
        const expectedDigest = required(expectedSourceHistorySha256, "expectedSourceHistorySha256", 64);
        if (sourceId === targetId) throw new Error("Source and target run IDs must be distinct");
        if (!/^[a-f0-9]{64}$/.test(expectedDigest)) {
          throw new TypeError("Expected source history SHA-256 must be lowercase hexadecimal");
        }
        const state = await readState();
        const task = state.tasks[reference];
        if (!task) throw new Error("Task reference does not exist");
        const source = task.runs[sourceId];
        if (!source || source.threadId !== expectedThread || !Array.isArray(source.history)) {
          throw new Error("Source run thread does not match the requested history fork");
        }
        if (typeof task.promptTurnId !== "string" || !task.promptTurnId.trim()) {
          throw new Error("Task prompt identity is unavailable for a history fork");
        }
        validateBuilderHistory(source.history, sourceId, source.threadId, task.promptTurnId,
          task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
        if (source.history.some(turn => Object.hasOwn(turn, "origin"))) {
          throw new Error("Nested imported histories cannot be used as a v1 source; manual reconciliation required");
        }
        const actualDigest = historyManifestSha256(reference, sourceId, source.threadId, task, source.history);
        if (actualDigest !== expectedDigest) {
          throw new Error("Source history changed after manifest inspection; no fork was committed");
        }
        const targetThreadId = "nine-builders:" + targetId;
        if (targetThreadId === source.threadId) {
          throw new Error("Target thread identity must differ from the source thread");
        }
        const existing = task.runs[targetId];
        const forkMetadata = {
          kind: "ENCRYPTED_VAULT_RUN_FORK",
          sourceTaskReference: reference,
          sourceRunId: sourceId,
          sourceThreadId: source.threadId,
          sourceHistorySha256: actualDigest,
          importedTurnCount: source.history.length
        };
        if (existing) {
          const prior = existing.originFork;
          if (!prior || prior.kind !== forkMetadata.kind ||
              prior.sourceTaskReference !== reference ||
              prior.sourceRunId !== sourceId ||
              prior.sourceThreadId !== source.threadId ||
              prior.sourceHistorySha256 !== actualDigest ||
              prior.importedTurnCount !== source.history.length ||
              existing.threadId !== targetThreadId ||
              !Array.isArray(existing.history) ||
              existing.history.length < source.history.length) {
            throw new Error("Target run ID already exists with conflicting history or fork provenance");
          }
          const expectedImported = source.history.map(turn => ({
            ...structuredClone(turn),
            runId: targetId,
            threadId: targetThreadId,
            origin: {
              kind: "ENCRYPTED_VAULT_RUN_FORK",
              sourceTaskReference: reference,
              sourceRunId: sourceId,
              sourceThreadId: source.threadId,
              sourceTurnId: turn.turnId ?? turn.checkpointId
            }
          }));
          if (expectedImported.some((turn, index) =>
            JSON.stringify(existing.history[index]) !== JSON.stringify(turn))) {
            throw new Error("Existing target history does not match the verified source manifest");
          }
          validateBuilderHistory(existing.history, targetId, targetThreadId, task.promptTurnId,
            task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
          return {
            durable: true,
            duplicate: true,
            sourceRunId: sourceId,
            targetRunId: targetId,
            sourceThreadId: source.threadId,
            targetThreadId,
            sourceHistorySha256: actualDigest,
            importedTurnCount: source.history.length
          };
        }

        const importedHistory = source.history.map(turn => ({
          ...structuredClone(turn),
          runId: targetId,
          threadId: targetThreadId,
          origin: {
            kind: "ENCRYPTED_VAULT_RUN_FORK",
            sourceTaskReference: reference,
            sourceRunId: sourceId,
            sourceThreadId: source.threadId,
            sourceTurnId: turn.turnId ?? turn.checkpointId
          }
        }));
        validateBuilderHistory(importedHistory, targetId, targetThreadId, task.promptTurnId,
          task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
        task.runs[targetId] = {
          threadId: targetThreadId,
          history: importedHistory,
          originFork: {
            ...forkMetadata,
            forkedAt: new Date(clock()).toISOString()
          }
        };
        await writeState(state);
        return {
          durable: true,
          duplicate: false,
          sourceRunId: sourceId,
          targetRunId: targetId,
          sourceThreadId: source.threadId,
          targetThreadId,
          sourceHistorySha256: actualDigest,
          importedTurnCount: importedHistory.length
        };
      });
    },

    async getTask(taskReference, { runId } = {}) {
      return serializeMutation(async () => {
      const reference = required(taskReference, "taskReference", 256);
      const id = required(runId, "runId", 120);
      const state = await readState();
      const task = state.tasks[reference];
      if (!task) throw new Error("Task reference does not exist");
      if (typeof task.promptTurnId !== "string" || !task.promptTurnId.trim()) {
        task.promptTurnId = "task-prompt-" + randomBytes(16).toString("hex");
      }
      const run = task.runs[id] ??= {
        threadId: "nine-builders:" + id,
        history: []
      };
      if (!task.runs[id].threadId || !Array.isArray(task.runs[id].history)) {
        throw new Error("Task vault run history is malformed");
      }
      validateBuilderHistory(run.history, id, run.threadId, task.promptTurnId,
        task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
      if (!state.tasks[reference].consent) {
        // Return the missing consent state; the executor will fail closed.
      }
      await writeState(state);
      return {
        prompt: task.prompt,
        promptTurnId: task.promptTurnId,
        promptCreatedAt: task.promptCreatedAt ?? null,
        threadId: run.threadId,
        history: structuredClone(run.history),
        consent: task.consent,
        operationalLimitsSnapshot: task.operationalConstraintRegister
          ? { ...structuredClone(task.operationalConstraintRegister), register_status: "LOADED" }
          : null
      };
      });
    },

    async appendOperationalConstraint(taskReference, entry, runtimeEvaluation) {
      if (!entry || typeof entry !== "object" || Array.isArray(entry) ||
          entry.evidence_state === "VERIFIED" || entry.evidenceVerification != null) {
        throw new Error("VERIFIED constraint evidence can only be written through the configured read-only evidence verifier");
      }
      return persistOperationalConstraint(taskReference, entry, runtimeEvaluation);
    },

    async verifyAndAppendOperationalConstraint(taskReference, entry, runtimeEvaluation) {
      if (!evidenceGateway) {
        throw new Error("No trusted read-only evidence gateway is configured; constraint remains unverified");
      }
      if (!entry || typeof entry !== "object" || Array.isArray(entry) ||
          Object.hasOwn(entry, "evidence_state") || Object.hasOwn(entry, "evidenceVerification")) {
        throw new TypeError("Verified constraint input must not supply its own evidence status or verification proof");
      }
      const verification = await verifyBuilderEvidenceReference(
        entry.source_reference, evidenceGateway, { timeoutMs: evidenceTimeoutMs }
      );
      if (!verification.verified || verification.status !== "VERIFIED" ||
          verification.verificationMethod !== "READ_ONLY_GITHUB_GATEWAY") {
        throw new Error("Constraint source verification " + verification.status + ": " + verification.reason);
      }
      const checkedAt = clock();
      if (!(checkedAt instanceof Date) || !Number.isFinite(checkedAt.getTime())) {
        throw new TypeError("Vault clock must return a valid Date for evidence verification");
      }
      const verifiedEntry = { ...entry, evidence_state: "VERIFIED" };
      const evidenceVerification = { ...verification, checkedAt: checkedAt.toISOString() };
      return persistOperationalConstraint(taskReference, verifiedEntry, runtimeEvaluation, evidenceVerification);
    },

    async appendBuilderResult(taskReference, record) {
      return serializeMutation(async () => {
      const reference = required(taskReference, "taskReference", 256);
      if (!record || typeof record !== "object") throw new TypeError("Builder result record is required");
      const runId = required(record.runId, "runId", 120);
      const role = required(record.role, "role", 32);
      const idempotencyKey = required(record.idempotencyKey, "idempotencyKey", 256);
      if (idempotencyKey !== runId + ":" + role) throw new Error("Builder idempotency key does not match run and role");
      const state = await readState();
      const task = state.tasks[reference];
      if (!task) throw new Error("Task reference does not exist");
      const run = task.runs[runId];
      if (!run || run.threadId !== record.threadId) throw new Error("Builder result does not belong to an initialized run thread");
      if (role === "BALDR") {
        const invocationId = required(record.reviewInvocationId, "reviewInvocationId", 128);
        const events = state.baldrReviewEvents ?? [];
        const starts = events.filter(event =>
          event.eventType === "INVOCATION_STARTED" &&
          event.reviewInvocationId === invocationId &&
          event.taskReference === reference && event.runId === runId &&
          event.threadId === run.threadId
        );
        const completed = events.some(event =>
          event.eventType === "INVOCATION_COMPLETED" &&
          event.reviewInvocationId === invocationId
        );
        if (starts.length !== 1 || completed) {
          throw new Error("Baldr result requires one active, matching encrypted invocation start");
        }
      } else if (record.reviewInvocationId != null) {
        throw new Error("Only Baldr results may carry a reviewInvocationId");
      }
      const reply = normalizedReply(record.reply);
      const prior = run.history.find(turn => turn.idempotencyKey === idempotencyKey);
      if (prior) {
        if (role === "BALDR" && prior.reviewInvocationId !== record.reviewInvocationId) {
          throw new Error("Baldr idempotency key is bound to a different invocation");
        }
        if (JSON.stringify(normalizedReply(prior.reply)) !== JSON.stringify(reply)) {
          throw new Error("Idempotency key already exists with a different Builder result; manual reconciliation required");
        }
        return { durable: true, checkpointId: prior.checkpointId };
      }
      validateBuilderHistory(run.history, runId, run.threadId, task.promptTurnId,
        task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);
      validateBuilderReplyAgainstHistory(reply, run.history, role, task.promptTurnId, task.prompt);
      let evidenceVerification = record.evidenceVerification ?? null;
      if (reply.turn_type === "CORRECTION_UPDATE") {
        const correction = JSON.parse(reply.body);
        const expectedEvidence = parseBuilderEvidenceReference(correction.artifact_reference);
        if (!evidenceVerification || evidenceVerification.verified !== true ||
            evidenceVerification.status !== "VERIFIED" ||
            evidenceVerification.verificationMethod !== "READ_ONLY_GITHUB_GATEWAY" ||
            evidenceVerification.kind !== expectedEvidence.kind ||
            evidenceVerification.repository !== expectedEvidence.repository ||
            evidenceVerification.objectId !== expectedEvidence.objectId ||
            evidenceVerification.branch !== expectedEvidence.branch ||
            evidenceVerification.workflow !== expectedEvidence.workflow ||
            evidenceVerification.expectedSha !== expectedEvidence.expectedSha) {
          throw new Error("Correction update cannot be persisted without matching successful evidence-gateway verification");
        }
        const checkedAt = required(evidenceVerification.checkedAt, "Evidence verification checkedAt", 64);
        const checkedAtDate = new Date(checkedAt);
        if (!Number.isFinite(checkedAtDate.getTime()) || checkedAtDate.toISOString() !== checkedAt) {
          throw new TypeError("Evidence verification checkedAt must be canonical ISO-8601");
        }
      }
      const replyBody = reply.body;
      const checkpointId = "vault-" + randomBytes(16).toString("hex");
      const completedAt = required(record.completedAt, "completedAt", 64);
      const completedDate = new Date(completedAt);
      if (!Number.isFinite(completedDate.getTime()) || completedDate.toISOString() !== completedAt) {
        throw new TypeError("completedAt must be a canonical ISO-8601 timestamp");
      }
      if (evidenceVerification?.checkedAt != null) {
        const checkedAt = required(evidenceVerification.checkedAt, "Evidence verification checkedAt", 64);
        const checkedDate = new Date(checkedAt);
        if (!Number.isFinite(checkedDate.getTime()) || checkedDate.toISOString() !== checkedAt ||
            checkedDate.getTime() > completedDate.getTime()) {
          throw new TypeError("Evidence verification checkedAt must precede the persisted Builder result");
        }
      }
      const priorTimestamp = [...run.history].reverse().find(turn =>
        Number.isSafeInteger(turn.timestampEpochMillis) ? turn.timestampEpochMillis > 0 :
          (typeof turn.completedAt === "string" && Number.isFinite(Date.parse(turn.completedAt)))
      );
      let promptTimestampMillis = null;
      if (task.promptCreatedAt != null) {
        const promptCreatedAt = required(task.promptCreatedAt, "promptCreatedAt", 64);
        const promptDate = new Date(promptCreatedAt);
        if (!Number.isFinite(promptDate.getTime()) || promptDate.toISOString() !== promptCreatedAt) {
          throw new TypeError("promptCreatedAt must be a canonical ISO-8601 timestamp");
        }
        promptTimestampMillis = promptDate.getTime();
      }
      const priorTimestampMillis = priorTimestamp
        ? (Number.isSafeInteger(priorTimestamp.timestampEpochMillis)
            ? priorTimestamp.timestampEpochMillis : Date.parse(priorTimestamp.completedAt))
        : promptTimestampMillis;
      if (priorTimestampMillis != null && completedDate.getTime() <= priorTimestampMillis) {
        throw new Error("Builder completion timestamp must increase strictly in run-thread order");
      }
      run.history.push({
        protocolVersion: 4,
        runId, threadId: run.threadId, idempotencyKey, checkpointId,
        turnId: checkpointId,
        timestampEpochMillis: completedDate.getTime(),
        speaker: role,
        reviewInvocationId: record.reviewInvocationId ?? null,
        body: replyBody,
        type: reply.turn_type,
        recipient: reply.recipient,
        reply_to_turn_id: reply.reply_to_turn_id,
        evidence_status: reply.evidence_status,
        evidenceVerification,
        reply,
        requestedProviderId: record.requestedProviderId,
        requestedModel: record.requestedModel,
        providerReportedModel: record.providerReportedModel,
        responseSha256: record.responseSha256,
        completedAt: record.completedAt
      });
      await writeState(state);
      return { durable: true, checkpointId };
      });
    },

    async beginBaldrReviewInvocation(taskReference, receipt) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        if (!receipt || typeof receipt !== "object" || receipt.reviewerRole !== "BALDR") {
          throw new TypeError("A typed Baldr invocation start receipt is required");
        }
        const runId = required(receipt.runId, "runId", 120);
        const threadId = required(receipt.threadId, "threadId", 256);
        const invocationId = required(receipt.reviewInvocationId, "reviewInvocationId", 128);
        const state = await readState();
        const task = state.tasks[reference];
        if (!task || !task.runs[runId] || task.runs[runId].threadId !== threadId) {
          throw new Error("Baldr invocation does not match an initialized vault run");
        }
        state.baldrReviewEvents ??= [];
        if (!Array.isArray(state.baldrReviewEvents) ||
            state.baldrReviewEvents.some(event => event.reviewInvocationId === invocationId)) {
          throw new Error("Baldr review invocation ID is already in use");
        }
        const priorStarts = state.baldrReviewEvents.filter(event =>
          event.eventType === "INVOCATION_STARTED" &&
          event.taskReference === reference && event.runId === runId
        );
        const completedIds = new Set(state.baldrReviewEvents
          .filter(event => event.eventType === "INVOCATION_COMPLETED")
          .map(event => event.reviewInvocationId));
        if (priorStarts.some(event => !completedIds.has(event.reviewInvocationId))) {
          throw new Error("A prior Baldr invocation has an unknown outcome; manual reconciliation required");
        }
        if (priorStarts.length > 0) {
          throw new Error("A Baldr invocation already exists for this run; review is not repeatable");
        }
        const allowed = [
          "reviewInvocationId", "runId", "taskReference", "threadId", "reviewerRole",
          "inputManifestSha256", "requestedProviderId", "requestedEndpointIdentity",
          "requestedModelId", "invokedAt", "provenanceLevel",
          "inputManifest", "reviewInput"
        ];
        const manifest = receipt.inputManifest;
        const manifestSha256 = manifest && createHash("sha256")
          .update(JSON.stringify(manifest), "utf8").digest("hex");
        const reviewInputSha256 = typeof receipt.reviewInput === "string"
          ? createHash("sha256").update(receipt.reviewInput, "utf8").digest("hex") : null;
        if (Object.keys(receipt).some(key => !allowed.includes(key)) ||
            receipt.taskReference !== reference || receipt.provenanceLevel !== "ROUTE_SEPARATED" ||
            !/^[a-f0-9]{64}$/.test(receipt.inputManifestSha256 || "") ||
            manifestSha256 !== receipt.inputManifestSha256 ||
            !manifest || manifest.schema !== "yggdrasil.baldr-review-manifest.v1" ||
            manifest.runId !== runId || manifest.taskReference !== reference ||
            manifest.reviewerRole !== "BALDR" || typeof manifest.promptTurnId !== "string" ||
            !manifest.promptTurnId.trim() ||
            manifest.requestedProviderId !== receipt.requestedProviderId ||
            manifest.requestedEndpointIdentity !== receipt.requestedEndpointIdentity ||
            manifest.requestedModelId !== receipt.requestedModelId ||
            !Array.isArray(manifest.peerOutcomeRefs) || manifest.peerOutcomeRefs.length !== 8 ||
            manifest.peerOutcomeRefs.some((item, index) => {
              const roles = ["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"];
              if (!item || item.role !== roles[index] ||
                  !["succeeded", "failed", "skipped"].includes(item.status)) return true;
              if (item.status === "succeeded") {
                return typeof item.outcomeRef !== "string" ||
                  !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(item.outcomeRef);
              }
              return item.outcomeRef != null;
            }) ||
            reviewInputSha256 !== manifest.requestBodySha256 ||
            !["BALDR"].includes(receipt.reviewerRole)) {
          throw new TypeError("Baldr invocation start receipt contains invalid or unrecognized metadata");
        }
        const invokedAt = required(receipt.invokedAt, "invokedAt", 64);
        if (new Date(invokedAt).toISOString() !== invokedAt) {
          throw new TypeError("Baldr invokedAt must be canonical ISO-8601");
        }
        state.baldrReviewEvents.push({
          eventType: "INVOCATION_STARTED",
          ...structuredClone(receipt)
        });
        await writeState(state);
        return { durable: true, reviewInvocationId: invocationId };
      });
    },

    async completeBaldrReviewInvocation(taskReference, completion) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        if (!completion || typeof completion !== "object") {
          throw new TypeError("Baldr invocation completion is required");
        }
        const invocationId = required(completion.reviewInvocationId, "reviewInvocationId", 128);
        const state = await readState();
        const events = state.baldrReviewEvents ?? [];
        const start = events.find(event =>
          event.eventType === "INVOCATION_STARTED" &&
          event.reviewInvocationId === invocationId &&
          event.taskReference === reference
        );
        if (!start || events.some(event =>
          event.eventType === "INVOCATION_COMPLETED" &&
          event.reviewInvocationId === invocationId
        )) {
          throw new Error("Baldr invocation start is missing or already completed");
        }
        const task = state.tasks[reference];
        const run = task?.runs?.[start.runId];
        const matchingTurns = Array.isArray(run?.history) ? run.history.filter(turn =>
          turn.speaker === "BALDR" &&
          (turn.checkpointId ?? turn.turnId) === completion.vaultTurnId &&
          turn.reviewInvocationId === invocationId &&
          turn.responseSha256 === completion.responseSha256
        ) : [];
        if (matchingTurns.length !== 1) {
          throw new Error("Baldr completion must reference exactly one matching durable vault turn");
        }
        const allowed = [
          "reviewInvocationId", "inputManifestSha256", "responseSha256",
          "vaultTurnId", "providerRequestId", "provenanceLevel",
          "status", "completedAt"
        ];
        if (Object.keys(completion).some(key => !allowed.includes(key)) ||
            completion.inputManifestSha256 !== start.inputManifestSha256 ||
            completion.status !== "COMPLETED" ||
            completion.provenanceLevel !== "INVOCATION_ATTRIBUTED" ||
            !/^[a-f0-9]{64}$/.test(completion.responseSha256 || "") ||
            !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(completion.vaultTurnId || "")) {
          throw new TypeError("Baldr invocation completion is invalid or does not match its start");
        }
        const completedAt = required(completion.completedAt, "completedAt", 64);
        const completedDate = new Date(completedAt);
        if (!Number.isFinite(completedDate.getTime()) ||
            completedDate.toISOString() !== completedAt ||
            completedDate.getTime() < Date.parse(start.invokedAt)) {
          throw new TypeError("Baldr completion timestamp is invalid");
        }
        if (completion.providerRequestId != null &&
            (typeof completion.providerRequestId !== "string" ||
             !/^[A-Za-z0-9._:-]{1,256}$/.test(completion.providerRequestId))) {
          throw new TypeError("Provider request ID is invalid");
        }
        events.push({
          eventType: "INVOCATION_COMPLETED",
          ...structuredClone(completion),
          taskReference: reference,
          runId: start.runId
        });
        await writeState(state);
        return { durable: true, reviewInvocationId: invocationId };
      });
    },

    async getBaldrReviewInvocation(taskReference, { runId, reviewInvocationId } = {}) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        const id = required(runId, "runId", 120);
        const invocationId = required(reviewInvocationId, "reviewInvocationId", 128);
        const state = await readState();
        const events = (state.baldrReviewEvents ?? []).filter(event =>
          event.taskReference === reference &&
          event.runId === id &&
          event.reviewInvocationId === invocationId
        );
        const start = events.find(event => event.eventType === "INVOCATION_STARTED");
        const completion = events.find(event => event.eventType === "INVOCATION_COMPLETED");
        if (!start) return null;
        return {
          start: structuredClone(start),
          completion: completion ? structuredClone(completion) : null
        };
      });
    },

    async appendUserAdoption(taskReference, {
      runId,
      correctionId,
      authorization
    } = {}) {
      return serializeMutation(async () => {
        const reference = required(taskReference, "taskReference", 256);
        const id = required(runId, "runId", 120);
        const correction = required(correctionId, "correctionId", 256);
        const nowDate = new Date(clock());
        if (!Number.isFinite(nowDate.getTime())) throw new TypeError("Vault clock must return a valid date");
        const verifiedAction = verifyUserAdoptionAuthorization(authorization, {
          publicKeys: authorizationKeys,
          now: nowDate.getTime(),
          maxAgeMs: userAuthorizationMaxAgeMs
        });
        if (verifiedAction.taskReference !== reference || verifiedAction.runId !== id ||
            verifiedAction.correctionId !== correction) {
          throw new Error("Signed user action does not match the requested task, run, and correction");
        }

        const state = await readState();
        const task = state.tasks[reference];
        if (!task) throw new Error("Task reference does not exist");
        const run = task.runs[id];
        if (!run || run.threadId !== verifiedAction.threadId) {
          throw new Error("Signed user action does not match an initialized run thread");
        }
        validateBuilderHistory(run.history, id, run.threadId, task.promptTurnId,
          task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt, reference);

        if (!state.usedUserActionNonces || typeof state.usedUserActionNonces !== "object" ||
            Array.isArray(state.usedUserActionNonces)) state.usedUserActionNonces = {};
        const priorUse = state.usedUserActionNonces[verifiedAction.nonce];
        if (priorUse) {
          const priorTurn = run.history.find(turn =>
            turn.userAuthorization?.nonce === verifiedAction.nonce &&
            turn.userAuthorization?.signatureSha256 === verifiedAction.signatureSha256
          );
          if (priorTurn) {
            return {
              durable: true,
              turnId: priorTurn.turnId,
              adoptedAt: priorTurn.completedAt,
              idempotentReplay: true
            };
          }
          throw new Error("User action nonce has already been consumed");
        }
        if (Object.keys(state.usedUserActionNonces).length >= 10000) {
          throw new RangeError("User action replay ledger reached its 10,000-entry safety limit");
        }

        let latest = null;
        let latestBody = null;
        for (const turn of run.history) {
          if (turn.type !== "CORRECTION_UPDATE") continue;
          let body;
          try { body = JSON.parse(turn.body); } catch { continue; }
          if (body?.correction_id === correction) {
            latest = turn;
            latestBody = body;
          }
        }
        if (!latest || latestBody?.stage !== "VERIFIED" || latest.speaker !== "BALDR" ||
            latest.evidenceVerification?.verified !== true ||
            latest.evidenceVerification?.status !== "VERIFIED") {
          throw new Error("Only the latest independently verified Baldr correction can be adopted");
        }
        const adoptedAt = verifiedAction.issuedAt;
        const adoptedMillis = verifiedAction.issuedAtEpochMillis;
        const priorTurn = run.history.at(-1);
        const priorMillis = priorTurn
          ? (Number.isSafeInteger(priorTurn.timestampEpochMillis)
              ? priorTurn.timestampEpochMillis
              : Date.parse(priorTurn.completedAt))
          : (task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt));
        if (priorMillis != null && adoptedMillis <= priorMillis) {
          throw new Error("Signed user-action timestamp must follow the latest run-thread record");
        }

        const adoptionBody = JSON.stringify({
          correction_id: correction,
          incident_turn_id: required(latestBody.incident_turn_id, "Verified incident ID", 256),
          stage: "ADOPTED",
          evidence: "The authenticated user explicitly accepted this verified correction record.",
          artifact_reference: "user-adoption:" + verifiedAction.nonce,
          acceptance_criteria_result: "The authenticated user accepted the latest Baldr-verified correction as project practice.",
          unresolved: required(latestBody.unresolved, "Verified correction unresolved", 4096)
        });
        const turnId = "user-adoption-" + verifiedAction.nonce;
        const adoptionTurn = {
          protocolVersion: 4,
          runId: id,
          threadId: run.threadId,
          turnId,
          speaker: "USER",
          body: adoptionBody,
          type: "CORRECTION_UPDATE",
          recipient: null,
          reply_to_turn_id: latest.turnId,
          evidence_status: "ADOPTED",
          timestampEpochMillis: adoptedMillis,
          completedAt: adoptedAt,
          userAuthorization: verifiedAction
        };
        validateBuilderHistory([...run.history, adoptionTurn], id, run.threadId,
          task.promptTurnId,
          task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt);
        run.history.push(adoptionTurn);
        state.usedUserActionNonces[verifiedAction.nonce] = {
          taskReference: reference,
          runId: id,
          threadId: run.threadId,
          correctionId: correction,
          userId: verifiedAction.userId,
          signatureSha256: verifiedAction.signatureSha256,
          issuedAt: verifiedAction.issuedAt
        };
        await writeState(state);
        return { durable: true, turnId, adoptedAt, userId: verifiedAction.userId };
      });
    }
  });
}

let defaultVault;
function configuredVault() {
  if (!defaultVault) {
    let userAuthorizationPublicKeys = {};
    const encodedKeys = process.env.BUILDER_USER_AUTHORIZATION_PUBLIC_KEYS_JSON;
    if (encodedKeys) {
      userAuthorizationPublicKeys = JSON.parse(encodedKeys);
      if (!userAuthorizationPublicKeys || typeof userAuthorizationPublicKeys !== "object" ||
          Array.isArray(userAuthorizationPublicKeys)) {
        throw new TypeError("BUILDER_USER_AUTHORIZATION_PUBLIC_KEYS_JSON must be a key-ID map");
      }
    }
    defaultVault = createEncryptedTaskVault({ userAuthorizationPublicKeys });
  }
  return defaultVault;
}
export async function getTask(taskReference, context) {
  return configuredVault().getTask(taskReference, context);
}
export async function appendOperationalConstraint(taskReference, entry, runtimeEvaluation) {
  return configuredVault().appendOperationalConstraint(taskReference, entry, runtimeEvaluation);
}
export async function getRunHistoryManifest(taskReference, context) {
  return configuredVault().getRunHistoryManifest(taskReference, context);
}
export async function forkRunHistory(taskReference, options) {
  return configuredVault().forkRunHistory(taskReference, options);
}

export async function appendBuilderResult(taskReference, record) {
  return configuredVault().appendBuilderResult(taskReference, record);
}

export async function beginBaldrReviewInvocation(taskReference, receipt) {
  return configuredVault().beginBaldrReviewInvocation(taskReference, receipt);
}

export async function completeBaldrReviewInvocation(taskReference, completion) {
  return configuredVault().completeBaldrReviewInvocation(taskReference, completion);
}

export async function getBaldrReviewInvocation(taskReference, context) {
  return configuredVault().getBaldrReviewInvocation(taskReference, context);
}

export async function appendUserAdoption(taskReference, request) {
  return configuredVault().appendUserAdoption(taskReference, request);
}
