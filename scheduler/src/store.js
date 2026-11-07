import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const EMPTY_STATE = Object.freeze({
  version: 3, tasks: [], runs: [], handoffs: [], authorities: [], authorityTransfers: []
});
const STORE_QUEUES = new WeakMap();

/**
 * Serialize read-modify-write operations across components sharing one store
 * instance. This is process-local coordination, not cross-process fencing.
 */
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

export class JsonFileStore {
  constructor(filePath) {
    if (typeof filePath !== "string" || !filePath.trim()) throw new TypeError("A state file path is required");
    this.filePath = filePath;
    this.writeQueue = Promise.resolve();
  }

  async load() {
    try {
      const text = await readFile(this.filePath, "utf8");
      return normalizeState(JSON.parse(text));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      return structuredClone(EMPTY_STATE);
    }
  }

  async save(state) {
    const normalized = normalizeState(state);
    const operation = this.writeQueue.then(async () => {
      await mkdir(dirname(this.filePath), { recursive: true });
      const temporaryPath = `${this.filePath}.${process.pid}.tmp`;
      await writeFile(temporaryPath, JSON.stringify(normalized, null, 2) + "\n", { encoding: "utf8", mode: 0o600 });
      await rename(temporaryPath, this.filePath);
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
