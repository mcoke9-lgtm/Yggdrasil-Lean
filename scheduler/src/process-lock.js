import { open, readFile, unlink, rename, lstat } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { dirname } from "node:path";
import { mkdir } from "node:fs/promises";

async function inspectOwner(lockPath) {
  let owner;
  try {
    const info = await lstat(lockPath);
    if (!info.isFile() || info.isSymbolicLink()) {
      throw new Error("Scheduler lock must be a regular non-symlink file");
    }
    if (process.platform !== "win32" && (info.mode & 0o077) !== 0) {
      throw new Error("Scheduler lock permissions are too broad; use 0600");
    }
    owner = JSON.parse(await readFile(lockPath, "utf8"));
  }
  catch (error) {
    if (error.code === "ENOENT") return null;
    throw new Error("Scheduler lock exists but cannot be safely inspected; do not delete it until the scheduler process is confirmed stopped.");
  }
  if (!Number.isInteger(owner.pid) || owner.pid < 1 ||
      typeof owner.token !== "string" || !owner.token) {
    throw new Error("Scheduler lock is malformed; refusing to start a second instance.");
  }
  return owner;
}

/**
 * A newly-created lock pathname is visible before its owner finishes writing
 * the JSON record. Retry that brief publication window; persistent malformed,
 * unsafe, or unreadable locks still fail closed.
 */
async function inspectOwnerAfterContention(lockPath) {
  let lastError;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      return await inspectOwner(lockPath);
    } catch (error) {
      lastError = error;
      if (attempt === 9) break;
      await new Promise(resolveDelay => setTimeout(resolveDelay, 10));
    }
  }
  throw lastError;
}

function assertOwnerStopped(owner) {
  try {
    process.kill(owner.pid, 0);
    throw new Error("Scheduler already appears to be running as PID " + owner.pid);
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
}

/**
 * Serialize stale-lock reclamation so two new processes cannot both unlink a
 * replacement lock after observing the same dead PID.
 */
async function reclaimStaleLock(lockPath, expectedToken) {
  const recoveryPath = lockPath + ".recovery";
  let recoveryHandle;
  try {
    recoveryHandle = await open(recoveryPath, "wx", 0o600);
  } catch (error) {
    if (error.code === "EEXIST") {
      throw new Error("Scheduler stale-lock recovery is already in progress; refusing a competing start.");
    }
    throw error;
  }
  try {
    const current = await inspectOwner(lockPath);
    if (!current) return true;
    if (current.token !== expectedToken) return false;
    assertOwnerStopped(current);
    try { await unlink(lockPath); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    return true;
  } finally {
    await recoveryHandle.close();
    try { await unlink(recoveryPath); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
  }
}

export async function acquireProcessLock(lockPath) {
  const directory = dirname(lockPath);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  if (process.platform !== "win32") {
    const directoryInfo = await lstat(directory);
    if (!directoryInfo.isDirectory() || directoryInfo.isSymbolicLink() ||
        (directoryInfo.mode & 0o077) !== 0) {
      throw new Error("Scheduler lock directory must be a regular owner-only directory (0700)");
    }
  }
  const token = randomUUID();
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const handle = await open(lockPath, "wx", 0o600);
      const now = new Date().toISOString();
      const owner = { pid: process.pid, token, createdAt: now, heartbeatAt: now };
      try {
        await handle.writeFile(JSON.stringify(owner) + "\n");
        await handle.sync();
      } finally {
        await handle.close();
      }
      if (process.platform !== "win32") {
        const directoryHandle = await open(directory, "r");
        try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
      }
      let released = false;
      let queue = Promise.resolve();
      const serialize = operation => {
        const result = queue.then(operation, operation);
        queue = result.catch(() => {});
        return result;
      };
      const release = () => serialize(async () => {
        if (released) return;
        const current = await inspectOwner(lockPath);
        if (!current || current.token !== token || current.pid !== process.pid) {
          released = true;
          return;
        }
        await unlink(lockPath);
        if (process.platform !== "win32") {
          const directoryHandle = await open(directory, "r");
          try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
        }
        released = true;
      });
      release.heartbeat = () => serialize(async () => {
        if (released) throw new Error("Scheduler process lock has already been released");
        const current = await inspectOwner(lockPath);
        if (!current || current.token !== token || current.pid !== process.pid) {
          throw new Error("Scheduler process lock ownership was lost");
        }
        const updated = { ...current, heartbeatAt: new Date().toISOString() };
        const temporaryPath = lockPath + "." + token + ".heartbeat.tmp";
        const temporary = await open(temporaryPath, "wx", 0o600);
        try {
          await temporary.writeFile(JSON.stringify(updated) + "\n");
          await temporary.sync();
        } finally {
          await temporary.close();
        }
        await rename(temporaryPath, lockPath);
        if (process.platform !== "win32") {
          const directoryHandle = await open(directory, "r");
          try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
        }
        return updated.heartbeatAt;
      });
      release.token = token;
      release.pid = process.pid;
      return release;
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      const existing = await inspectOwnerAfterContention(lockPath);
      if (!existing) continue;
      try {
        assertOwnerStopped(existing);
      } catch (probeError) {
        if (probeError.code !== "ESRCH") throw probeError;
      }
      const reclaimed = await reclaimStaleLock(lockPath, existing.token);
      if (!reclaimed) continue;
    }
  }
  throw new Error("Could not acquire scheduler process lock after bounded stale-lock recovery attempts");
}
