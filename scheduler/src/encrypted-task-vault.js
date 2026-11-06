import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { mkdir, open, readFile, rename, lstat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { validateBuilderHistory, validateBuilderReplyAgainstHistory } from "./builder-turn-history.js";
import { parseBuilderEvidenceReference } from "./builder-evidence-reference.js";

const MAGIC = "YGGDRASIL-BUILDER-VAULT";
const VERSION = 1;
const MAX_VAULT_BYTES = 8 * 1024 * 1024;

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
  key = keyFromEnvironment()
} = {}) {
  const path = resolve(required(filePath, "BUILDER_TASK_VAULT_FILE", 4096));
  if (!Buffer.isBuffer(key) || key.length !== 32) throw new TypeError("Task vault key must be exactly 32 bytes");

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

  return Object.freeze({
    async initializeTask(taskReference, prompt) {
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
    },

    async approveTask(taskReference, fingerprint, approvedAt = new Date().toISOString()) {
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
    },

    async getTask(taskReference, { runId } = {}) {
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
        operationalLimitsSnapshot: null
      };
    },

    async appendBuilderResult(taskReference, record) {
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
      const reply = normalizedReply(record.reply);
      const prior = run.history.find(turn => turn.idempotencyKey === idempotencyKey);
      if (prior) {
        if (JSON.stringify(normalizedReply(prior.reply)) !== JSON.stringify(reply)) {
          throw new Error("Idempotency key already exists with a different Builder result; manual reconciliation required");
        }
        return { durable: true, checkpointId: prior.checkpointId };
      }
      validateBuilderHistory(run.history, runId, run.threadId, task.promptTurnId,
        task.promptCreatedAt == null ? null : Date.parse(task.promptCreatedAt), task.prompt);
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
    }
  });
}

let defaultVault;
function configuredVault() {
  if (!defaultVault) defaultVault = createEncryptedTaskVault();
  return defaultVault;
}
export async function getTask(taskReference, context) {
  return configuredVault().getTask(taskReference, context);
}
export async function appendBuilderResult(taskReference, record) {
  return configuredVault().appendBuilderResult(taskReference, record);
}
