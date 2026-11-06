import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, chmod, symlink, lstat, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { JsonFileStore } from "../src/store.js";

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "scheduler-json-store-"));
  await chmod(directory, 0o700);
  return { directory, filePath: join(directory, "state.json") };
}

test("JSON store atomically persists private state and reloads it", async () => {
  const f = await fixture();
  try {
    const store = new JsonFileStore(f.filePath);
    const state = { version: 1, tasks: [{ id: "task-1" }], runs: [], builderRuns: [{ runId: "builder-1" }] };
    await store.save(state);
    assert.deepEqual(await store.load(), state);
    if (process.platform !== "win32") {
      assert.equal((await lstat(f.filePath)).mode & 0o777, 0o600);
      assert.equal((await lstat(f.directory)).mode & 0o777, 0o700);
    }
    assert.deepEqual(await readdir(f.directory), ["state.json"]);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("JSON store serializes concurrent writes and leaves a valid final document", async () => {
  const f = await fixture();
  try {
    const store = new JsonFileStore(f.filePath);
    await Promise.all([
      store.save({ version: 1, tasks: [{ id: "first" }], runs: [] }),
      store.save({ version: 1, tasks: [{ id: "second" }], runs: [] })
    ]);
    assert.deepEqual((await store.load()).tasks, [{ id: "second" }]);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("JSON store rejects symlink state files and permissive directories", async () => {
  const f = await fixture();
  try {
    const target = join(f.directory, "target.json");
    await new JsonFileStore(target).save({ version: 1, tasks: [], runs: [] });
    await symlink(target, f.filePath);
    await assert.rejects(() => new JsonFileStore(f.filePath).load(), /regular non-symlink/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }

  const broad = await fixture();
  try {
    await new JsonFileStore(broad.filePath).save({ version: 1, tasks: [], runs: [] });
    if (process.platform !== "win32") {
      await chmod(broad.directory, 0o755);
      await assert.rejects(() => new JsonFileStore(broad.filePath).load(), /permissions are too broad/);
    }
  } finally {
    await rm(broad.directory, { recursive: true, force: true });
  }
});

test("JSON store rejects invalid persisted shapes", async () => {
  const f = await fixture();
  try {
    await (await import("node:fs/promises")).writeFile(f.filePath, JSON.stringify({
      version: 99, tasks: [], runs: []
    }), { mode: 0o600 });
    await assert.rejects(() => new JsonFileStore(f.filePath).load(), /unsupported or invalid shape/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});
