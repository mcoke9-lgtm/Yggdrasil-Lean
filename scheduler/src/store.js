import { mkdir, readFile, rename, open, lstat, unlink } from "node:fs/promises";
import { dirname } from "node:path";
import { randomUUID } from "node:crypto";

const EMPTY_STATE = Object.freeze({ version: 1, tasks: [], runs: [] });
const MAX_STATE_BYTES = 16 * 1024 * 1024;

async function ensurePrivateDirectory(directory) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  if (process.platform !== "win32") {
    const info = await lstat(directory);
    if (!info.isDirectory() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) {
      throw new Error("Scheduler state directory must be a regular owner-only directory (0700)");
    }
  }
}

export class JsonFileStore {
  constructor(filePath) {
    if (typeof filePath !== "string" || !filePath.trim()) throw new TypeError("A state file path is required");
    this.filePath = filePath;
    this.writeQueue = Promise.resolve();
  }

  async load() {
    try {
      const directory = dirname(this.filePath);
      const directoryInfo = await lstat(directory);
      if (!directoryInfo.isDirectory() || directoryInfo.isSymbolicLink()) {
        throw new Error("Scheduler state parent must be a regular directory");
      }
      if (process.platform !== "win32" && (directoryInfo.mode & 0o077) !== 0) {
        throw new Error("Scheduler state directory permissions are too broad; use 0700");
      }
      const info = await lstat(this.filePath);
      if (!info.isFile() || info.isSymbolicLink()) {
        throw new Error("Scheduler state must be a regular non-symlink file");
      }
      if (process.platform !== "win32" && (info.mode & 0o077) !== 0) {
        throw new Error("Scheduler state file permissions are too broad; use 0600");
      }
      if (info.size > MAX_STATE_BYTES) throw new RangeError("Scheduler state exceeds 16 MiB");
      const state = JSON.parse(await readFile(this.filePath, "utf8"));
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
      const directory = dirname(this.filePath);
      await ensurePrivateDirectory(directory);
      const json = JSON.stringify(state, null, 2) + "\n";
      const bytes = Buffer.from(json, "utf8");
      if (bytes.length > MAX_STATE_BYTES) throw new RangeError("Scheduler state exceeds 16 MiB");
      const temporaryPath = this.filePath + "." + process.pid + "." + randomUUID() + ".tmp";
      const handle = await open(temporaryPath, "wx", 0o600);
      try {
        await handle.writeFile(bytes);
        await handle.sync();
      } finally {
        await handle.close();
      }
      try {
        await rename(temporaryPath, this.filePath);
        if (process.platform !== "win32") {
          const directoryHandle = await open(directory, "r");
          try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
        }
      } catch (error) {
        try { await unlink(temporaryPath); }
        catch (cleanupError) { if (cleanupError.code !== "ENOENT") throw cleanupError; }
        throw error;
      }
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
