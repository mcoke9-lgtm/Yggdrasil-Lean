import test from "node:test";
import assert from "node:assert/strict";
import { RepositoryCoordinationRegistry } from "./registry.js";

const SHA = "a".repeat(40);
const SHA_B = "b".repeat(40);

test("two sessions can register and read the same immutable repository baseline", async () => {
  const registry = new RepositoryCoordinationRegistry();
  await Promise.all([
    registry.registerSession({ sessionId: "chat-A", chatLabel: "Chat A", builderRoles: ["Muninn"] }),
    registry.registerSession({ sessionId: "chat-B", chatLabel: "Chat B", builderRoles: ["Kvasir"] })
  ]);

  const [a, b] = await Promise.all([
    registry.requestRead({
      sessionId: "chat-A", taskId: "task-A", repository: "mcoke9-lgtm/Yggdrasil-Lean",
      ref: "main", baseCommitSha: SHA, paths: ["README.md"]
    }),
    registry.requestRead({
      sessionId: "chat-B", taskId: "task-B", repository: "mcoke9-lgtm/Yggdrasil-Lean",
      ref: "main", baseCommitSha: SHA, paths: ["README.md", "docs/"]
    })
  ]);

  assert.equal(a.access, "read_only");
  assert.equal(b.access, "read_only");
  assert.equal(a.baseCommitSha, b.baseCommitSha);
  assert.equal((await registry.listActiveSessions()).length, 2);
});

test("concurrent checkpoint attempts cannot silently overwrite one another", async () => {
  const registry = new RepositoryCoordinationRegistry();
  await registry.registerSession({ sessionId: "chat-A", chatLabel: "Chat A" });
  await registry.requestRead({
    sessionId: "chat-A", taskId: "task-A", repository: "mcoke9-lgtm/Yggdrasil-Lean",
    ref: "main", baseCommitSha: SHA
  });

  const results = await Promise.allSettled([
    registry.recordCheckpoint({
      sessionId: "chat-A", taskId: "task-A", expectedRevision: 1,
      checkpoint: { note: "checkpoint one" }, observedCommitSha: SHA_B
    }),
    registry.recordCheckpoint({
      sessionId: "chat-A", taskId: "task-A", expectedRevision: 1,
      checkpoint: { note: "checkpoint two" }
    })
  ]);

  assert.equal(results.filter(result => result.status === "fulfilled").length, 1);
  const rejected = results.find(result => result.status === "rejected");
  assert.equal(rejected.reason.code, "REVISION_CONFLICT");
  const task = (await registry.listActiveSessions())[0].tasks[0];
  assert.equal(task.revision, 2);
  assert.ok(["checkpoint one", "checkpoint two"].includes(task.checkpoint.note));
});

test("a different session cannot checkpoint another session's task", async () => {
  const registry = new RepositoryCoordinationRegistry();
  await registry.registerSession({ sessionId: "chat-A", chatLabel: "Chat A" });
  await registry.registerSession({ sessionId: "chat-B", chatLabel: "Chat B" });
  await registry.requestRead({
    sessionId: "chat-A", taskId: "task-A", repository: "mcoke9-lgtm/Yggdrasil-Lean",
    ref: "main", baseCommitSha: SHA
  });

  await assert.rejects(
    registry.recordCheckpoint({
      sessionId: "chat-B", taskId: "task-A", expectedRevision: 1, checkpoint: { note: "unauthorized" }
    }),
    /does not own this task/
  );
});

test("audit events are ordered, append-only snapshots and detached from callers", async () => {
  const registry = new RepositoryCoordinationRegistry();
  await registry.registerSession({ sessionId: "chat-A", chatLabel: "Chat A" });
  const log = await registry.exportAuditLog();
  assert.deepEqual(log.map(event => event.sequence), [1]);
  log[0].payload.chatLabel = "tampered";
  assert.equal((await registry.exportAuditLog())[0].payload.chatLabel, "Chat A");
});

test("invalid or missing commit identity is rejected", async () => {
  const registry = new RepositoryCoordinationRegistry();
  await registry.registerSession({ sessionId: "chat-A", chatLabel: "Chat A" });
  await assert.rejects(
    registry.requestRead({
      sessionId: "chat-A", taskId: "task-A", repository: "mcoke9-lgtm/Yggdrasil-Lean",
      ref: "main", baseCommitSha: "main"
    }),
    /full 40-character Git commit SHA/
  );
});
