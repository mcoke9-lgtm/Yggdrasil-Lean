import test from "node:test";
import { spawn } from "node:child_process";
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
    }), { mode: 0o600 });
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

test("waits briefly for a newly-created lock owner record to finish publishing", async () => {
  const f = await fixture();
  let release;
  try {
    await writeFile(f.lockPath, "", { mode: 0o600 });
    const publishOwner = new Promise((resolve, reject) => {
      setTimeout(() => writeFile(f.lockPath, JSON.stringify({
        pid: 999999999, token: "publishing-owner",
        createdAt: new Date().toISOString(), heartbeatAt: new Date().toISOString()
      }), { mode: 0o600 }).then(resolve, reject), 25);
    });
    release = await acquireProcessLock(f.lockPath);
    await publishOwner;
    const owner = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(owner.pid, process.pid);
    assert.equal(owner.token, release.token);
  } finally {
    if (release) await release();
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("malformed or uninspectable locks fail closed", async () => {
  const f = await fixture();
  try {
    await writeFile(f.lockPath, "{not-json", { mode: 0o600 });
    await assert.rejects(() => acquireProcessLock(f.lockPath), /cannot be safely inspected/);
    await writeFile(f.lockPath, JSON.stringify({ pid: 0, token: "bad" }), { mode: 0o600 });
    await assert.rejects(() => acquireProcessLock(f.lockPath), /malformed/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});


test("fails closed when a stale-lock recovery marker already exists", async () => {
  const f = await fixture();
  try {
    await writeFile(f.lockPath, JSON.stringify({
      pid: 999999999, token: "stale-owner",
      createdAt: "2026-01-01T00:00:00.000Z",
      heartbeatAt: "2026-01-01T00:00:00.000Z"
    }), { mode: 0o600 });
    await writeFile(f.lockPath + ".recovery", "recovery-in-progress", { mode: 0o600 });
    await assert.rejects(
      () => acquireProcessLock(f.lockPath),
      /stale-lock recovery is already in progress/
    );
    const stillPresent = JSON.parse(await readFile(f.lockPath, "utf8"));
    assert.equal(stillPresent.token, "stale-owner");
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("reclaims a process lock after its owner exits abruptly without leaving recovery debris", async () => {
  const f = await fixture();
  const modulePath = new URL("../src/process-lock.js", import.meta.url).href;
  const script = `
    import { acquireProcessLock } from ${JSON.stringify(modulePath)};
    await acquireProcessLock(process.argv[1]);
    process.exit(0);
  `;
  try {
    await new Promise((resolveChild, rejectChild) => {
      const child = spawn(process.execPath, ["--input-type=module", "-e", script, f.lockPath], {
        stdio: ["ignore", "ignore", "pipe"]
      });
      let stderr = "";
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", chunk => { stderr += chunk; });
      child.once("error", rejectChild);
      child.once("close", code => code === 0
        ? resolveChild()
        : rejectChild(new Error("Lock owner child exited " + code + ": " + stderr)));
    });
    const release = await acquireProcessLock(f.lockPath);
    try {
      const owner = JSON.parse(await readFile(f.lockPath, "utf8"));
      assert.equal(owner.pid, process.pid);
      assert.equal(owner.token, release.token);
      await assert.rejects(
        () => readFile(f.lockPath + ".recovery"),
        error => error.code === "ENOENT"
      );
    } finally {
      await release();
    }
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});
