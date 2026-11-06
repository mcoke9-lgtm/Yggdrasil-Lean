import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { acquireProcessLock } from "../src/process-lock.js";

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-process-lock-"));
  return { directory, lockPath: join(directory, "scheduler.lock") };
}

test("process lock records ownership and exposes a verifiable heartbeat", async () => {
  const f = await fixture();
  let release;
  try {
    release = await acquireProcessLock(f.lockPath);
    const first = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(first.pid, process.pid);
    assert.equal(first.token, release.token);
    assert.ok(Number.isFinite(Date.parse(first.createdAt)));
    assert.ok(Number.isFinite(Date.parse(first.heartbeatAt)));
    await assert.rejects(() => acquireProcessLock(f.lockPath), /already appears to be running/);
    await new Promise(resolve => setTimeout(resolve, 5));
    const heartbeatAt = await release.heartbeat();
    const updated = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(updated.heartbeatAt, heartbeatAt);
    assert.ok(Date.parse(updated.heartbeatAt) >= Date.parse(first.heartbeatAt));
  } finally {
    if (release) await release();
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("release removes only the lock owned by the current token", async () => {
  const f = await fixture();
  const release = await acquireProcessLock(f.lockPath);
  try {
    await writeFile(f.lockPath, JSON.stringify({
      pid: process.pid, token: "replacement-owner", createdAt: new Date().toISOString(),
      heartbeatAt: new Date().toISOString()
    }));
    await assert.rejects(() => release.heartbeat(), /ownership was lost/);
    await release();
    const replacement = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(replacement.token, "replacement-owner");
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("reclaims a stale lock only after serializing recovery and confirming the PID is gone", async () => {
  const f = await fixture();
  try {
    await writeFile(f.lockPath, JSON.stringify({
      pid: 999999999, token: "stale-owner", createdAt: "2026-01-01T00:00:00.000Z",
      heartbeatAt: "2026-01-01T00:00:00.000Z"
    }));
    const results = await Promise.allSettled([
      acquireProcessLock(f.lockPath),
      acquireProcessLock(f.lockPath)
    ]);
    const acquired = results.filter(result => result.status === "fulfilled");
    assert.equal(acquired.length, 1);
    const current = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(current.pid, process.pid);
    assert.notEqual(current.token, "stale-owner");
    await acquired[0].value();
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("malformed or uninspectable locks fail closed", async () => {
  const f = await fixture();
  try {
    await writeFile(f.lockPath, "{not-json");
    await assert.rejects(() => acquireProcessLock(f.lockPath), /cannot be safely inspected/);
    await writeFile(f.lockPath, JSON.stringify({ pid: 0, token: "bad" }));
    await assert.rejects(() => acquireProcessLock(f.lockPath), /malformed/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});
