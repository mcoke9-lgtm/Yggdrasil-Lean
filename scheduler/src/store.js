import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const EMPTY_STATE = Object.freeze({ version: 2, tasks: [], runs: [], handoffs: [] });

function normalizeState(state) {
  if (!state || !Array.isArray(state.tasks) || !Array.isArray(state.runs)) {
    throw new Error("Scheduler state has an unsupported or invalid shape");
  }
  if (state.version === 1) {
    return {
      version: 2,
      tasks: state.tasks,
      runs: state.runs,
      handoffs: []
    };
  }
  if (state.version === 2 && Array.isArray(state.handoffs)) return state;
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
