import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, chmod } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createEncryptedTaskVault } from "../src/encrypted-task-vault.js";

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-vault-"));
  await chmod(directory, 0o700);
  const filePath = join(directory, "tasks.vault");
  const key = Buffer.alloc(32, 7);
  return { directory, filePath, key, vault: createEncryptedTaskVault({ filePath, key }) };
}

test("encrypted vault requires explicit approval and preserves isolated run history", async () => {
  const f = await fixture();
  try {
    await f.vault.initializeTask("task:pilot", "A private scheduled task prompt.");
    const before = await f.vault.getTask("task:pilot", { runId: "run-001" });
    assert.equal(before.consent, null);
    assert.equal(before.history.length, 0);
    assert.match(before.threadId, /run-001/);
    assert.match(before.promptTurnId, /^task-prompt-/);
    const promptTurnId = before.promptTurnId;
    await f.vault.approveTask("task:pilot", "a".repeat(64), "2026-11-05T20:00:00.000Z");
    const approved = await f.vault.getTask("task:pilot", { runId: "run-001" });
    assert.equal(approved.consent.status, "USER_APPROVED");
    assert.equal(approved.consent.fingerprint, "a".repeat(64));
    assert.equal(approved.promptTurnId, promptTurnId, "task prompt identity must persist across vault reads");
    assert.equal(approved.history.length, 0);
    const otherRun = await f.vault.getTask("task:pilot", { runId: "run-002" });
    assert.notEqual(otherRun.threadId, approved.threadId);
    assert.equal(otherRun.promptTurnId, promptTurnId, "task prompt identity is stable across runs");
    assert.equal(otherRun.history.length, 0);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("Builder result persistence is durable and idempotent by run-role key", async () => {
  const f = await fixture();
  try {
    await f.vault.initializeTask("task:pilot", "Task prompt.");
    await f.vault.getTask("task:pilot", { runId: "run-001" });
    const record = {
      idempotencyKey: "run-001:ODIN", runId: "run-001",
      threadId: "nine-builders:run-001", role: "ODIN",
      requestedProviderId: "provider", requestedModel: "odin-model",
      providerReportedModel: "reported-model", responseSha256: "b".repeat(64),
      reply: { body: "Odin result", turn_type: "CONTRIBUTION", evidence_status: "PROPOSED" },
      completedAt: "2026-11-05T20:00:00.000Z"
    };
    const first = await f.vault.appendBuilderResult("task:pilot", record);
    const second = await f.vault.appendBuilderResult("task:pilot", record);
    assert.equal(first.durable, true);
    assert.deepEqual(second, first);
    const task = await f.vault.getTask("task:pilot", { runId: "run-001" });
    assert.equal(task.history.length, 1);
    assert.equal(task.history[0].speaker, "ODIN");
    assert.equal(task.history[0].body, "Odin result");
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("vault ciphertext does not expose task prompt and rejects wrong keys", async () => {
  const f = await fixture();
  try {
    await f.vault.initializeTask("task:pilot", "DO NOT STORE THIS IN PLAINTEXT");
    const bytes = await readFile(f.filePath);
    assert.equal(bytes.includes(Buffer.from("DO NOT STORE THIS IN PLAINTEXT")), false);
    const wrong = createEncryptedTaskVault({ filePath: f.filePath, key: Buffer.alloc(32, 8) });
    await assert.rejects(() => wrong.getTask("task:pilot", { runId: "run-001" }));
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("task setup can safely retry the same prompt but rejects a changed prompt", async () => {
  const f = await fixture();
  try {
    const first = await f.vault.initializeTask("task:pilot", "Original prompt.");
    const retry = await f.vault.initializeTask("task:pilot", "Original prompt.");
    assert.equal(first.approvalRequired, true);
    assert.equal(retry.approvalRequired, true);
    await assert.rejects(
      () => f.vault.initializeTask("task:pilot", "Replacement prompt."),
      /different prompt/
    );
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});
