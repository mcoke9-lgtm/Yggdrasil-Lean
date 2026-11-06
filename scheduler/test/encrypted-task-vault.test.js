import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, chmod } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { generateKeyPairSync, sign } from "node:crypto";
import { createEncryptedTaskVault } from "../src/encrypted-task-vault.js";
import { serializeUserAdoptionPayload } from "../src/user-adoption-authorization.js";

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-vault-"));
  await chmod(directory, 0o700);
  const filePath = join(directory, "tasks.vault");
  const key = Buffer.alloc(32, 7);
  return { directory, filePath, key, vault: createEncryptedTaskVault({ filePath, key }) };
}


async function correctionReadyFixture({ includeVerification = true } = {}) {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-adoption-"));
  await chmod(directory, 0o700);
  const filePath = join(directory, "tasks.vault");
  const key = Buffer.alloc(32, 9);
  const pair = generateKeyPairSync("ed25519");
  const publicPem = pair.publicKey.export({ type: "spki", format: "pem" });
  const keyId = "adoption-key-1";
  const fixedNow = new Date(Date.now() + 60000);
  const vault = createEncryptedTaskVault({
    filePath, key,
    userAuthorizationPublicKeys: { [keyId]: publicPem },
    clock: () => fixedNow
  });
  const taskReference = "task:adoption-pilot";
  const runId = "run-adoption-001";
  const prompt = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident-1\nReview the recorded incident.";
  await vault.initializeTask(taskReference, prompt);
  const task = await vault.getTask(taskReference, { runId });
  let nextTime = Date.parse(task.promptCreatedAt) + 1000;
  async function append(role, turnType, evidenceStatus, body, replyTo, evidenceVerification = null) {
    const completedAt = new Date(nextTime).toISOString();
    const record = {
      runId, role, idempotencyKey: runId + ":" + role, threadId: task.threadId,
      reply: {
        body: JSON.stringify(body), turn_type: turnType, evidence_status: evidenceStatus,
        recipient: "USER", reply_to_turn_id: replyTo
      },
      completedAt
    };
    if (evidenceVerification) record.evidenceVerification = {
      ...evidenceVerification,
      checkedAt: new Date(nextTime - 1).toISOString()
    };
    nextTime += 1000;
    return vault.appendBuilderResult(taskReference, record);
  }
  const incident = await append("HODR", "IMPROVEMENT_INCIDENT", "OBSERVED", {
    incident_summary: "Wrong result", observed_behavior: "A", expected_behavior: "B",
    evidence: "test output", unknowns: "none", suspected_causes: "mapping",
    proposed_next_step: "review", status: "OPEN_REVIEW"
  }, task.promptTurnId);
  const plan = await append("TYR", "CORRECTION_PLAN", "PROPOSED", {
    correction_id: "correction-001", incident_turn_id: incident.checkpointId,
    objective: "Fix mapping", acceptance_criteria: "test passes", steps: "change code",
    owner: "MARI", rollback_condition: "regression", status: "PROPOSED"
  }, incident.checkpointId);
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const commitRef = "github+commit://github.com/acme/project/" + sha + "?branch=correction";
  const actionsRef = "github+actions://github.com/acme/project/runs/12345?workflow=scheduler-tests&sha=" + sha;
  const commitProof = {
    verified: true, status: "VERIFIED", verificationMethod: "READ_ONLY_GITHUB_GATEWAY",
    kind: "COMMIT", repository: "acme/project", objectId: sha,
    branch: "correction", workflow: null, expectedSha: null
  };
  const actionsProof = {
    verified: true, status: "VERIFIED", verificationMethod: "READ_ONLY_GITHUB_GATEWAY",
    kind: "ACTIONS_RUN", repository: "acme/project", objectId: "12345",
    branch: null, workflow: "scheduler-tests", expectedSha: sha
  };
  const implemented = await append("MARI", "CORRECTION_UPDATE", "IMPLEMENTED", {
    correction_id: "correction-001", incident_turn_id: incident.checkpointId,
    stage: "IMPLEMENTED", evidence: "The mapping change is committed.",
    artifact_reference: commitRef, acceptance_criteria_result: "Implementation matches plan.",
    unresolved: "Testing remains."
  }, plan.checkpointId, commitProof);
  const tested = await append("MUNINN", "CORRECTION_UPDATE", "TESTED", {
    correction_id: "correction-001", incident_turn_id: incident.checkpointId,
    stage: "TESTED", evidence: "The scheduler suite passed.",
    artifact_reference: actionsRef, acceptance_criteria_result: "The regression test passed.",
    unresolved: "Independent review remains."
  }, implemented.checkpointId, actionsProof);
  let verified = null;
  if (includeVerification) {
    verified = await append("BALDR", "CORRECTION_UPDATE", "VERIFIED", {
      correction_id: "correction-001", incident_turn_id: incident.checkpointId,
      stage: "VERIFIED", evidence: "Baldr independently reviewed the evidence.",
      artifact_reference: actionsRef, acceptance_criteria_result: "Independent review passed.",
      unresolved: "User adoption remains."
    }, tested.checkpointId, actionsProof);
  }
  function authorize(overrides = {}) {
    const assertion = {
      version: 1, action: "ADOPT_CORRECTION", keyId, userId: "user-opaque-1",
      taskReference, runId, threadId: task.threadId, correctionId: "correction-001",
      nonce: "0123456789abcdefghijklmnopqrstuv",
      issuedAt: fixedNow.toISOString(), signature: "", ...overrides
    };
    assertion.signature = sign(
      null, Buffer.from(serializeUserAdoptionPayload(assertion), "utf8"), pair.privateKey
    ).toString("base64url");
    return assertion;
  }
  return { directory, vault, taskReference, runId, task, fixedNow, authorize, verified };
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
    assert.ok(Number.isFinite(Date.parse(before.promptCreatedAt)));
    const promptTurnId = before.promptTurnId;
    const promptCreatedAt = before.promptCreatedAt;
    await f.vault.approveTask("task:pilot", "a".repeat(64),
      new Date(Date.parse(promptCreatedAt) + 500).toISOString());
    const approved = await f.vault.getTask("task:pilot", { runId: "run-001" });
    assert.equal(approved.consent.status, "USER_APPROVED");
    assert.equal(approved.consent.fingerprint, "a".repeat(64));
    assert.equal(approved.promptTurnId, promptTurnId, "task prompt identity must persist across vault reads");
    assert.equal(approved.promptCreatedAt, promptCreatedAt, "task prompt source timestamp must persist");
    assert.equal(approved.history.length, 0);
    const otherRun = await f.vault.getTask("task:pilot", { runId: "run-002" });
    assert.notEqual(otherRun.threadId, approved.threadId);
    assert.equal(otherRun.promptTurnId, promptTurnId, "task prompt identity is stable across runs");
    assert.equal(otherRun.promptCreatedAt, promptCreatedAt, "task prompt timestamp is stable across runs");
    assert.equal(otherRun.history.length, 0);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("Builder result persistence is durable and idempotent by run-role key", async () => {
  const f = await fixture();
  try {
    await f.vault.initializeTask("task:pilot", "Task prompt.");
    const initialTask = await f.vault.getTask("task:pilot", { runId: "run-001" });
    const record = {
      idempotencyKey: "run-001:ODIN", runId: "run-001",
      threadId: "nine-builders:run-001", role: "ODIN",
      requestedProviderId: "provider", requestedModel: "odin-model",
      providerReportedModel: "reported-model", responseSha256: "b".repeat(64),
      reply: { body: "Odin result", turn_type: "CONTRIBUTION", evidence_status: "PROPOSED" },
      completedAt: new Date(Date.parse(initialTask.promptCreatedAt) + 1000).toISOString()
    };
    const first = await f.vault.appendBuilderResult("task:pilot", record);
    const second = await f.vault.appendBuilderResult("task:pilot", record);
    assert.equal(first.durable, true);
    assert.deepEqual(second, first);
    const task = await f.vault.getTask("task:pilot", { runId: "run-001" });
    assert.equal(task.history.length, 1);
    assert.equal(task.history[0].speaker, "ODIN");
    assert.equal(task.history[0].body, "Odin result");
    assert.equal(task.history[0].protocolVersion, 4);
    assert.equal(task.history[0].turnId, task.history[0].checkpointId);
    assert.equal(task.history[0].timestampEpochMillis, Date.parse(task.history[0].completedAt));
    assert.equal(task.history[0].type, "CONTRIBUTION");
    assert.equal(task.history[0].evidence_status, "PROPOSED");
    assert.equal(task.history[0].recipient, null);
    assert.equal(task.history[0].reply_to_turn_id, null);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});


test("only a signed, fresh, task-bound user action can append adoption after Baldr verification", async () => {
  const f = await correctionReadyFixture();
  try {
    const authorization = f.authorize();
    const [first, retry] = await Promise.all([
      f.vault.appendUserAdoption(f.taskReference, {
        runId: f.runId, correctionId: "correction-001", authorization
      }),
      f.vault.appendUserAdoption(f.taskReference, {
        runId: f.runId, correctionId: "correction-001", authorization
      })
    ]);
    assert.equal(first.durable, true);
    assert.equal(retry.durable, true);
    assert.equal([first, retry].filter(result => result.idempotentReplay === true).length, 1);
    const task = await f.vault.getTask(f.taskReference, { runId: f.runId });
    const adoption = task.history.at(-1);
    assert.equal(adoption.speaker, "USER");
    assert.equal(adoption.type, "CORRECTION_UPDATE");
    assert.equal(adoption.evidence_status, "ADOPTED");
    assert.equal(adoption.reply_to_turn_id, f.verified.checkpointId);
    assert.equal(adoption.userAuthorization.verificationMethod, "ED25519_USER_ACTION_ASSERTION");
    assert.equal(JSON.parse(adoption.body).stage, "ADOPTED");
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("adoption fails closed without independent verification or a configured signing key", async () => {
  const f = await correctionReadyFixture({ includeVerification: false });
  try {
    await assert.rejects(() => f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization: f.authorize()
    }), /Only the latest independently verified Baldr correction can be adopted/);
    const noKeyVault = createEncryptedTaskVault({
      filePath: f.filePath, key: f.key,
      clock: () => f.fixedNow
    });
    await assert.rejects(() => noKeyVault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization: f.authorize()
    }), /signing key is not configured/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("adoption rejects a changed task binding, stale timestamp, and reused nonce", async () => {
  const f = await correctionReadyFixture();
  try {
    await assert.rejects(() => f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001",
      authorization: f.authorize({ taskReference: "task:other" })
    }), /does not match the requested task/);
    const stale = f.authorize({ issuedAt: new Date(f.fixedNow.getTime() - 300001).toISOString() });
    await assert.rejects(() => f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization: stale
    }), /expired or from the future/);
    const good = f.authorize();
    await f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization: good
    });
    const reused = f.authorize({ userId: "another-user" });
    await assert.rejects(() => f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization: reused
    }), /latest independently verified|nonce has already been consumed/);
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
