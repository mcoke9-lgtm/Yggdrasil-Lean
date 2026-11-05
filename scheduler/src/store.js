import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const EMPTY_STATE = Object.freeze({ version: 1, tasks: [], runs: [] });

export class JsonFileStore {
  constructor(filePath) {
    if (typeof filePath !== "string" || !filePath.trim()) throw new TypeError("A state file path is required");
    this.filePath = filePath;
    this.writeQueue = Promise.resolve();
  }

  async load() {
    try {
      const text = await readFile(this.filePath, "utf8");
      const state = JSON.parse(text);
      if (state?.version !== 1 || !Array.isArray(state.tasks) || !Array.isArray(state.runs)) {
        throw new Error("Scheduler state has an unsupported or invalid shape");
      }
      return state;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      return structuredClone(EMPTY_STATE);
    }
  }

  async save(state) {
    const operation = this.writeQueue.then(async () => {
      await mkdir(dirname(this.filePath), { recursive: true });
      const temporaryPath = `${this.filePath}.${process.pid}.tmp`;
      await writeFile(temporaryPath, JSON.stringify(state, null, 2) + "\n", { encoding: "utf8", mode: 0o600 });
      await rename(temporaryPath, this.filePath);
    });
    this.writeQueue = operation.catch(() => {});
    return operation;
  }
}

export class MemoryStore {
  constructor(initialState = EMPTY_STATE) {
    this.state = structuredClone(initialState);
  }
  async load() { return structuredClone(this.state); }
  async save(state) { this.state = structuredClone(state); }
}
