import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { acquireProcessLock } from "../src/process-lock.js";

test("exclusive lock blocks a second process until the owner releases it", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-process-lock-"));
  const path = join(directory, "scheduler.lock");
  const moduleUrl = new URL("../src/process-lock.js", import.meta.url).href;
  const script = `import { acquireProcessLock } from ${JSON.stringify(moduleUrl)};
    const release = await acquireProcessLock(process.argv[1]);
    process.stdout.write("LOCKED\\n");
    process.stdin.resume();
    process.stdin.on("end", async () => { await release(); process.exit(0); });`;
  const child = spawn(process.execPath, ["--input-type=module", "-e", script, path], {
    stdio: ["pipe", "pipe", "pipe"]
  });
  try {
    await new Promise((resolve, reject) => {
      let output = "";
      child.stdout.on("data", chunk => { output += chunk; if (output.includes("LOCKED")) resolve(); });
      child.once("error", reject);
      child.once("exit", code => { if (code !== 0) reject(new Error("Lock owner exited before acquiring lock")); });
    });
    await assert.rejects(() => acquireProcessLock(path), /Scheduler lock already exists/);
    child.stdin.end();
    const code = await new Promise(resolve => child.once("exit", resolve));
    assert.equal(code, 0);
    const release = await acquireProcessLock(path);
    await release();
  } finally {
    if (child.exitCode === null) child.kill("SIGKILL");
    await rm(directory, { recursive: true, force: true });
  }
});

test("malformed lock fails closed and is preserved for inspection", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-process-lock-"));
  const path = join(directory, "scheduler.lock");
  try {
    await writeFile(path, "{partial", { mode: 0o600 });
    await assert.rejects(() => acquireProcessLock(path), /cannot be safely inspected/);
    assert.equal(await readFile(path, "utf8"), "{partial");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("release does not remove a lock file whose ownership token changed", async () => {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-process-lock-"));
  const path = join(directory, "scheduler.lock");
  try {
    const release = await acquireProcessLock(path);
    await writeFile(path, JSON.stringify({ pid: process.pid, token: "replacement" }));
    await release();
    assert.equal(JSON.parse(await readFile(path, "utf8")).token, "replacement");
  } finally { await rm(directory, { recursive: true, force: true }); }
});
