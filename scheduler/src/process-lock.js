import { open, readFile, mkdir, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname } from "node:path";

/**
 * Acquire an exclusive lock for one local scheduler state path.
 *
 * Stale locks are never reclaimed automatically: PID reuse and check-then-unlink
 * races can remove a lock created by a competing starter. An operator must
 * verify that the recorded process is stopped before removing a stale lock.
 */
export async function acquireProcessLock(lockPath) {
  if (typeof lockPath !== "string" || !lockPath.trim()) {
    throw new TypeError("A non-empty lock path is required");
  }
  await mkdir(dirname(lockPath), { recursive: true });
  const token = randomUUID();
  let handle;
  try {
    handle = await open(lockPath, "wx", 0o600);
    await handle.writeFile(JSON.stringify({
      pid: process.pid, token, createdAt: new Date().toISOString()
    }) + "\n");
    await handle.sync();
    await handle.close();
    handle = null;
  } catch (error) {
    if (handle) await handle.close().catch(() => {});
    if (error.code === "EEXIST") {
      let existing;
      try { existing = JSON.parse(await readFile(lockPath, "utf8")); }
      catch {
        throw new Error("Scheduler lock exists but cannot be safely inspected; verify the scheduler is stopped before manually removing the lock.");
      }
      const pid = Number.isInteger(existing.pid) && existing.pid > 0 ? existing.pid : "unknown";
      throw new Error(`Scheduler lock already exists (recorded PID: ${pid}). Do not remove it until the owning process is confirmed stopped.`);
    }
    throw error;
  }

  let released = false;
  return async () => {
    if (released) return;
    released = true;
    let current;
    try { current = JSON.parse(await readFile(lockPath, "utf8")); }
    catch (error) { if (error.code === "ENOENT") return; throw error; }
    if (current.token === token && current.pid === process.pid) await unlink(lockPath);
  };
}
