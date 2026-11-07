import { createHash, randomUUID } from "node:crypto";
import { mkdir, open, readFile, rename, unlink } from "node:fs/promises";
import { dirname } from "node:path";

const EMPTY_STATE = Object.freeze({
  version: 3, tasks: [], runs: [], handoffs: [], authorities: [], authorityTransfers: []
});
const STORE_QUEUES = new WeakMap();

export function withStoreLock(store, operation) {
  if (!store || (typeof store !== "object" && typeof store !== "function")) {
    throw new TypeError("A shared store instance is required");
  }
  const previous = STORE_QUEUES.get(store) || Promise.resolve();
  const result = previous.then(operation);
  STORE_QUEUES.set(store, result.catch(() => {}));
  return result;
}

function normalizeState(state) {
  if (!state || !Array.isArray(state.tasks) || !Array.isArray(state.runs)) {
    throw new Error("Scheduler state has an unsupported or invalid shape");
  }
  if (state.version === 1) {
    return {
      version: 3, tasks: state.tasks, runs: state.runs, handoffs: [],
      authorities: [], authorityTransfers: []
    };
  }
  if (state.version === 2 && Array.isArray(state.handoffs)) {
    return {
      version: 3, tasks: state.tasks, runs: state.runs, handoffs: state.handoffs,
      authorities: [], authorityTransfers: []
    };
  }
  if (state.version === 3 && Array.isArray(state.handoffs) &&
      Array.isArray(state.authorities) && Array.isArray(state.authorityTransfers)) return state;
  throw new Error("Scheduler state has an unsupported or invalid shape");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function syncDirectory(directory) {
  const handle = await open(directory, "r");
  try { await handle.sync(); }
  finally { await handle.close(); }
}

async function durableReplace(targetPath, contents) {
  const temporaryPath = `${targetPath}.${process.pid}.${randomUUID()}.tmp`;
  let handle;
  try {
    handle = await open(temporaryPath, "wx", 0o600);
    await handle.writeFile(contents, "utf8");
    await handle.sync();
    await handle.close();
    handle = null;
    await rename(temporaryPath, targetPath);
    await syncDirectory(dirname(targetPath));
  } catch (error) {
    if (handle) await handle.close().catch(() => {});
    await unlink(temporaryPath).catch(() => {});
    throw error;
  }
}

async function readOptional(path) {
  try { return await readFile(path); }
  catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

/**
 * JSON state with a separately written local integrity sidecar.
 *
 * The sidecar detects accidental corruption and one-sided replacement/rollback.
 * Because it resides on the same host/filesystem, it is NOT a rollback-proof
 * witness: restoring both files from the same old snapshot can evade detection.
 */
export class JsonFileStore {
  constructor(filePath) {
    if (typeof filePath !== "string" || !filePath.trim()) throw new TypeError("A state file path is required");
    this.filePath = filePath;
    this.integrityPath = `${filePath}.integrity.json`;
    this.writeQueue = Promise.resolve();
  }

  async inspect() {
    const [stateBytes, integrityBytes] = await Promise.all([
      readOptional(this.filePath), readOptional(this.integrityPath)
    ]);
    if (stateBytes === null && integrityBytes === null) {
      return { state: structuredClone(EMPTY_STATE), revision: 0, initialized: false };
    }
    if (stateBytes === null || integrityBytes === null) {
      throw new Error("Scheduler state/integrity sidecar mismatch; quarantine required");
    }

    let integrity;
    try { integrity = JSON.parse(integrityBytes.toString("utf8")); }
    catch { throw new Error("Scheduler integrity sidecar is malformed; quarantine required"); }
    if (integrity.version !== 1 || !Number.isSafeInteger(integrity.revision) ||
        integrity.revision < 1 || !/^[a-f0-9]{64}$/.test(integrity.sha256 || "")) {
      throw new Error("Scheduler integrity sidecar has an unsupported or invalid shape; quarantine required");
    }
    if (sha256(stateBytes) !== integrity.sha256) {
      throw new Error("Scheduler state checksum mismatch; quarantine required");
    }
    let parsed;
    try { parsed = JSON.parse(stateBytes.toString("utf8")); }
    catch { throw new Error("Scheduler state JSON is malformed; quarantine required"); }
    return { state: normalizeState(parsed), revision: integrity.revision, initialized: true };
  }

  async load() {
    const result = await this.inspect();
    return result.state;
  }

  /**
   * Explicitly adopt a pre-sidecar state file after an operator supplies its
   * independently calculated SHA-256. Never invoked automatically at startup.
   */
  async adoptLegacyState({ expectedSha256 }) {
    if (!/^[a-f0-9]{64}$/.test(expectedSha256 || "")) {
      throw new TypeError("An independently calculated expectedSha256 is required");
    }
    const [stateBytes, integrityBytes] = await Promise.all([
      readOptional(this.filePath), readOptional(this.integrityPath)
    ]);
    if (stateBytes === null) throw new Error("No legacy state file exists to adopt");
    if (integrityBytes !== null) throw new Error("State already has an integrity sidecar");
    if (sha256(stateBytes) !== expectedSha256) {
      throw new Error("Legacy state SHA-256 does not match the supplied value");
    }
    let parsed;
    try { parsed = JSON.parse(stateBytes.toString("utf8")); }
    catch { throw new Error("Legacy state JSON is malformed; quarantine required"); }
    normalizeState(parsed);
    const integrity = {
      version: 1, revision: 1, sha256: expectedSha256,
      adoptedAt: new Date().toISOString()
    };
    await durableReplace(this.integrityPath, JSON.stringify(integrity, null, 2) + "\\n");
    return { revision: 1, sha256: expectedSha256, stateVersion: parsed.version };
  }

  async save(state) {
    const normalized = normalizeState(state);
    const operation = this.writeQueue.then(async () => {
      await mkdir(dirname(this.filePath), { recursive: true });
      const current = await this.inspect();
      const stateBytes = Buffer.from(JSON.stringify(normalized, null, 2) + "\n", "utf8");
      const nextIntegrity = {
        version: 1,
        revision: current.revision + 1,
        sha256: sha256(stateBytes),
        updatedAt: new Date().toISOString()
      };
      // A crash between these two durable replacements intentionally leaves a
      // detectable mismatch; startup then quarantines rather than guessing.
      await durableReplace(this.filePath, stateBytes);
      await durableReplace(this.integrityPath, JSON.stringify(nextIntegrity, null, 2) + "\n");
    });
    this.writeQueue = operation.catch(() => {});
    return operation;
  }
}

export class MemoryStore {
  constructor(initialState = EMPTY_STATE) {
    this.state = normalizeState(structuredClone(initialState));
  }
  async load() { return structuredClone(this.state); }
  async save(state) { this.state = structuredClone(normalizeState(state)); }
}
