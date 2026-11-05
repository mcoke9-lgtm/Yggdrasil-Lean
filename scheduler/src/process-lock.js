import { open, readFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname } from "node:path";
import { mkdir } from "node:fs/promises";

export async function acquireProcessLock(lockPath) {
  await mkdir(dirname(lockPath), { recursive: true });
  const token = randomUUID();
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const handle = await open(lockPath, "wx", 0o600);
      await handle.writeFile(JSON.stringify({ pid: process.pid, token, createdAt: new Date().toISOString() }) + "\n");
      await handle.close();
      return async () => {
        try {
          const current = JSON.parse(await readFile(lockPath, "utf8"));
          if (current.token === token && current.pid === process.pid) await unlink(lockPath);
        } catch (error) {
          if (error.code !== "ENOENT") throw error;
        }
      };
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      let existing;
      try { existing = JSON.parse(await readFile(lockPath, "utf8")); }
      catch { throw new Error("Scheduler lock exists but cannot be safely inspected; do not delete it until the scheduler process is confirmed stopped."); }
      if (!Number.isInteger(existing.pid) || existing.pid < 1) {
        throw new Error("Scheduler lock is malformed; refusing to start a second instance.");
      }
      try {
        process.kill(existing.pid, 0);
        throw new Error(`Scheduler already appears to be running as PID ${existing.pid}`);
      } catch (probeError) {
        if (probeError.code !== "ESRCH") throw probeError;
      }
      await unlink(lockPath);
    }
  }
  throw new Error("Could not acquire scheduler process lock");
}
