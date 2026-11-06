import test from "node:test";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, chmod } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createCipheriv, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { createEncryptedTaskVault } from "../src/encrypted-task-vault.js";
import { serializeUserAdoptionPayload } from "../src/user-adoption-authorization.js";
import { sha256 } from "../src/baldr-review-invocation.js";

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
  const baseTime = Date.parse("2026-11-06T00:00:00.000Z");
  const fixedNow = new Date(baseTime + 10000);
  const taskReference = "task:adoption-pilot";
  const runId = "run-adoption-001";
  const threadId = "nine-builders:" + runId;
  const promptTurnId = "task-prompt-1";
  const promptCreatedAt = new Date(baseTime).toISOString();
  const prompt = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident-1\nReview the recorded incident.";
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const commitRef = "github+commit://github.com/acme/project/" + sha + "?branch=correction";
  const actionsRef = "github+actions://github.com/acme/project/runs/12345?workflow=scheduler-tests&sha=" + sha;
  function turn(turnId, speaker, type, replyTo, body, evidenceStatus, time, proof = null) {
    const completedAt = new Date(time).toISOString();
    const reply = {
      body: JSON.stringify(body), turn_type: type, evidence_status: evidenceStatus,
      recipient: "USER", reply_to_turn_id: replyTo
    };
    return {
      protocolVersion: 4, runId, threadId, turnId, checkpointId: turnId,
      speaker, body: reply.body, type, recipient: "USER",
      reply_to_turn_id: replyTo, evidence_status: evidenceStatus,
      timestampEpochMillis: time, completedAt, reply,
      ...(proof ? { evidenceVerification: {
        ...proof, checkedAt: new Date(time - 1).toISOString()
      } } : {})
    };
  }
  const incident = turn("incident-1", "HODR", "IMPROVEMENT_INCIDENT", promptTurnId, {
    incident_summary: "Wrong result", observed_behavior: "A", expected_behavior: "B",
    evidence: "test output", unknowns: "none", suspected_causes: "mapping",
    proposed_next_step: "review", status: "OPEN_REVIEW"
  }, "OBSERVED", baseTime + 1000);
  const plan = turn("plan-1", "TYR", "CORRECTION_PLAN", "incident-1", {
    correction_id: "correction-001", incident_turn_id: "incident-1",
    objective: "Fix mapping", acceptance_criteria: "test passes", steps: "change code",
    owner: "MARI", rollback_condition: "regression", status: "PROPOSED"
  }, "PROPOSED", baseTime + 2000);
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
  const implemented = turn("implemented-1", "MARI", "CORRECTION_UPDATE", "plan-1", {
    correction_id: "correction-001", incident_turn_id: "incident-1",
    stage: "IMPLEMENTED", evidence: "The mapping change is committed.",
    artifact_reference: commitRef, acceptance_criteria_result: "Implementation matches plan.",
    unresolved: "Testing remains."
  }, "IMPLEMENTED", baseTime + 3000, commitProof);
  const tested = turn("tested-1", "MUNINN", "CORRECTION_UPDATE", "implemented-1", {
    correction_id: "correction-001", incident_turn_id: "incident-1",
    stage: "TESTED", evidence: "The scheduler suite passed.",
    artifact_reference: actionsRef, acceptance_criteria_result: "The regression test passed.",
    unresolved: "Independent review remains."
  }, "TESTED", baseTime + 4000, actionsProof);
  const verified = turn("verified-1", "BALDR", "CORRECTION_UPDATE", "tested-1", {
    correction_id: "correction-001", incident_turn_id: "incident-1",
    stage: "VERIFIED", evidence: "Baldr independently reviewed the evidence.",
    artifact_reference: actionsRef, acceptance_criteria_result: "Independent review passed.",
    unresolved: "User adoption remains."
  }, "VERIFIED", baseTime + 5000, actionsProof);
  const state = {
    version: 1,
    usedUserActionNonces: {},
    tasks: {
      [taskReference]: {
        prompt, promptTurnId, promptCreatedAt, consent: null,
        runs: { [runId]: { threadId, history: includeVerification
          ? [incident, plan, implemented, tested, verified]
          : [incident, plan, implemented, tested] } }
      }
    }
  };
  const magic = "YGGDRASIL-BUILDER-VAULT";
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(magic + ":1"));
  const plaintext = Buffer.from(JSON.stringify(state), "utf8");
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const encrypted = Buffer.concat([
    Buffer.from(magic + "\n1\n", "utf8"),
    iv, cipher.getAuthTag(), ciphertext
  ]);
  await writeFile(filePath, encrypted, { mode: 0o600 });
  const vault = createEncryptedTaskVault({
    filePath, key, userAuthorizationPublicKeys: { [keyId]: publicPem },
    clock: () => fixedNow
  });
  function authorize(overrides = {}) {
    const assertion = {
      version: 1, action: "ADOPT_CORRECTION", keyId, userId: "user-opaque-1",
      taskReference, runId, threadId, correctionId: "correction-001",
      nonce: "0123456789abcdefghijklmnopqrstuv",
      issuedAt: fixedNow.toISOString(), signature: "", ...overrides
    };
    assertion.signature = sign(
      null, Buffer.from(serializeUserAdoptionPayload(assertion), "utf8"), pair.privateKey
    ).toString("base64url");
    return assertion;
  }
  return { directory, filePath, key, vault, taskReference, runId, fixedNow, authorize, verified };
}

test("serializes vault mutations from separate processes without losing concurrent task creation", async () => {
  const h = await fixture();
  const modulePath = fileURLToPath(new URL("../src/encrypted-task-vault.js", import.meta.url));
  const script = `
    import { createEncryptedTaskVault } from ${JSON.stringify(modulePath)};
    const [filePath, encodedKey, taskReference, prompt] = process.argv.slice(1);
    const vault = createEncryptedTaskVault({
      filePath, key: Buffer.from(encodedKey, "base64")
    });
    await vault.initializeTask(taskReference, prompt);
  `;
  function createInChild(taskReference, prompt) {
    return new Promise((resolveChild, rejectChild) => {
      const child = spawn(process.execPath, [
        "--input-type=module", "-e", script,
        h.filePath, h.key.toString("base64"), taskReference, prompt
      ], { stdio: ["ignore", "ignore", "pipe"] });
      let stderr = "";
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", chunk => { stderr += chunk; });
      child.once("error", rejectChild);
      child.once("close", code => {
        if (code === 0) resolveChild();
        else rejectChild(new Error("Vault child exited " + code + ": " + stderr));
      });
    });
  }
  await Promise.all([
    createInChild("task-from-process-a", "prompt A"),
    createInChild("task-from-process-b", "prompt B")
  ]);
  const a = await h.vault.getTask("task-from-process-a", { runId: "run-a" });
  const b = await h.vault.getTask("task-from-process-b", { runId: "run-b" });
  assert.equal(a.prompt, "prompt A");
  assert.equal(b.prompt, "prompt B");
  await rm(h.directory, { recursive: true, force: true });
});

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

test("forks encrypted run history atomically while preserving turns and reply links", async () => {
  const f = await fixture();
  try {
    const taskReference = "task:history-fork";
    const sourceRunId = "legacy-run-001";
    const targetRunId = "continuation-run-001";
    await f.vault.initializeTask(taskReference, "Continue the recorded task.");
    const sourceTask = await f.vault.getTask(taskReference, { runId: sourceRunId });
    const firstAt = new Date(Date.parse(sourceTask.promptCreatedAt) + 1000).toISOString();
    const first = await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":ODIN",
      runId: sourceRunId, threadId: sourceTask.threadId, role: "ODIN",
      requestedProviderId: "fixture-provider", requestedModel: "odin-fixture",
      providerReportedModel: "odin-fixture", responseSha256: "a".repeat(64),
      reply: { body: "Original root contribution", turn_type: "CONTRIBUTION", evidence_status: "OBSERVED" },
      completedAt: firstAt
    });
    const secondAt = new Date(Date.parse(firstAt) + 1000).toISOString();
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":HODR",
      runId: sourceRunId, threadId: sourceTask.threadId, role: "HODR",
      requestedProviderId: "fixture-provider", requestedModel: "hodr-fixture",
      providerReportedModel: "hodr-fixture", responseSha256: "b".repeat(64),
      reply: {
        body: "A response linked to Odin",
        turn_type: "RESPONSE", evidence_status: "OBSERVED",
        recipient: "ODIN", reply_to_turn_id: first.checkpointId
      },
      completedAt: secondAt
    });
    const sourceBefore = await f.vault.getTask(taskReference, { runId: sourceRunId });
    const manifest = await f.vault.getRunHistoryManifest(taskReference, { runId: sourceRunId });
    const fork = await f.vault.forkRunHistory(taskReference, {
      sourceRunId, targetRunId,
      expectedSourceThreadId: manifest.threadId,
      expectedSourceHistorySha256: manifest.historySha256
    });
    assert.equal(fork.durable, true);
    assert.equal(fork.duplicate, false);
    assert.equal(fork.importedTurnCount, 2);
    assert.notEqual(fork.targetThreadId, fork.sourceThreadId);

    const target = await f.vault.getTask(taskReference, { runId: targetRunId });
    assert.equal(target.threadId, fork.targetThreadId);
    assert.equal(target.promptTurnId, sourceBefore.promptTurnId);
    assert.equal(target.promptCreatedAt, sourceBefore.promptCreatedAt);
    assert.equal(target.history.length, sourceBefore.history.length);
    for (let index = 0; index < sourceBefore.history.length; index += 1) {
      const imported = structuredClone(target.history[index]);
      const origin = imported.origin;
      delete imported.origin;
      imported.runId = sourceBefore.history[index].runId;
      imported.threadId = sourceBefore.history[index].threadId;
      assert.deepEqual(imported, sourceBefore.history[index]);
      assert.deepEqual(origin, {
        kind: "ENCRYPTED_VAULT_RUN_FORK",
        sourceTaskReference: taskReference,
        sourceRunId,
        sourceThreadId: sourceBefore.threadId,
        sourceTurnId: sourceBefore.history[index].turnId
      });
    }
    assert.equal(target.history[1].reply_to_turn_id, target.history[0].turnId);
    assert.equal(target.history[0].idempotencyKey, sourceBefore.history[0].idempotencyKey);

    const retry = await f.vault.forkRunHistory(taskReference, {
      sourceRunId, targetRunId,
      expectedSourceThreadId: manifest.threadId,
      expectedSourceHistorySha256: manifest.historySha256
    });
    assert.equal(retry.duplicate, true);

    const nextAt = new Date(Date.parse(secondAt) + 1000).toISOString();
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: targetRunId + ":TYR",
      runId: targetRunId, threadId: target.threadId, role: "TYR",
      requestedProviderId: "fixture-provider", requestedModel: "tyr-fixture",
      providerReportedModel: "tyr-fixture", responseSha256: "c".repeat(64),
      reply: { body: "New continuation contribution", turn_type: "CONTRIBUTION", evidence_status: "PROPOSED" },
      completedAt: nextAt
    });
    const continued = await f.vault.getTask(taskReference, { runId: targetRunId });
    assert.equal(continued.history.length, 3);
    assert.equal(continued.history[2].idempotencyKey, targetRunId + ":TYR");

    const sourceAfter = await f.vault.getTask(taskReference, { runId: sourceRunId });
    assert.deepEqual(sourceAfter.history, sourceBefore.history);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("history fork rejects a source changed after manifest inspection and creates no target", async () => {
  const f = await fixture();
  try {
    const taskReference = "task:history-fork-race";
    const sourceRunId = "legacy-race-001";
    const targetRunId = "continuation-race-001";
    await f.vault.initializeTask(taskReference, "Detect source history races.");
    const source = await f.vault.getTask(taskReference, { runId: sourceRunId });
    const firstAt = new Date(Date.parse(source.promptCreatedAt) + 1000).toISOString();
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":ODIN",
      runId: sourceRunId, threadId: source.threadId, role: "ODIN",
      requestedProviderId: "fixture-provider", requestedModel: "odin-fixture",
      providerReportedModel: "odin-fixture", responseSha256: "d".repeat(64),
      reply: { body: "First source result", turn_type: "CONTRIBUTION", evidence_status: "OBSERVED" },
      completedAt: firstAt
    });
    const manifest = await f.vault.getRunHistoryManifest(taskReference, { runId: sourceRunId });
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":HODR",
      runId: sourceRunId, threadId: source.threadId, role: "HODR",
      requestedProviderId: "fixture-provider", requestedModel: "hodr-fixture",
      providerReportedModel: "hodr-fixture", responseSha256: "e".repeat(64),
      reply: { body: "Changed source history", turn_type: "CONTRIBUTION", evidence_status: "OBSERVED" },
      completedAt: new Date(Date.parse(firstAt) + 1000).toISOString()
    });
    await assert.rejects(() => f.vault.forkRunHistory(taskReference, {
      sourceRunId, targetRunId,
      expectedSourceThreadId: manifest.threadId,
      expectedSourceHistorySha256: manifest.historySha256
    }), /Source history changed after manifest inspection/);
    await assert.rejects(
      () => f.vault.getRunHistoryManifest(taskReference, { runId: targetRunId }),
      /Source run history does not exist/
    );
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("concurrent identical history forks are idempotent and conflicting target reuse fails closed", async () => {
  const f = await fixture();
  try {
    const taskReference = "task:history-fork-concurrent";
    const sourceRunId = "legacy-concurrent-001";
    const targetRunId = "continuation-concurrent-001";
    await f.vault.initializeTask(taskReference, "Concurrent fork test.");
    const source = await f.vault.getTask(taskReference, { runId: sourceRunId });
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":ODIN",
      runId: sourceRunId, threadId: source.threadId, role: "ODIN",
      requestedProviderId: "fixture-provider", requestedModel: "odin-fixture",
      providerReportedModel: "odin-fixture", responseSha256: "f".repeat(64),
      reply: { body: "Concurrent source result", turn_type: "CONTRIBUTION", evidence_status: "OBSERVED" },
      completedAt: new Date(Date.parse(source.promptCreatedAt) + 1000).toISOString()
    });
    const manifest = await f.vault.getRunHistoryManifest(taskReference, { runId: sourceRunId });
    const secondVault = createEncryptedTaskVault({ filePath: f.filePath, key: f.key });
    const options = {
      sourceRunId, targetRunId,
      expectedSourceThreadId: manifest.threadId,
      expectedSourceHistorySha256: manifest.historySha256
    };
    const results = await Promise.all([
      f.vault.forkRunHistory(taskReference, options),
      secondVault.forkRunHistory(taskReference, options)
    ]);
    assert.equal(results.filter(item => item.duplicate === false).length, 1);
    assert.equal(results.filter(item => item.duplicate === true).length, 1);
    await assert.rejects(() => f.vault.forkRunHistory(taskReference, {
      ...options, expectedSourceHistorySha256: "0".repeat(64)
    }), /Source history changed after manifest inspection/);
    await assert.rejects(() => f.vault.forkRunHistory(taskReference, {
      ...options, expectedSourceThreadId: "nine-builders:wrong-source"
    }), /Source run thread does not match/);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("serializes competing history forks across separate processes", async () => {
  const f = await fixture();
  try {
    const taskReference = "task:history-fork-processes";
    const sourceRunId = "legacy-process-fork";
    const targetRunId = "continuation-process-fork";
    await f.vault.initializeTask(taskReference, "Cross-process history fork test.");
    const source = await f.vault.getTask(taskReference, { runId: sourceRunId });
    await f.vault.appendBuilderResult(taskReference, {
      idempotencyKey: sourceRunId + ":ODIN",
      runId: sourceRunId, threadId: source.threadId, role: "ODIN",
      requestedProviderId: "fixture-provider", requestedModel: "odin-fixture",
      providerReportedModel: "odin-fixture", responseSha256: "9".repeat(64),
      reply: { body: "Cross-process source turn", turn_type: "CONTRIBUTION", evidence_status: "OBSERVED" },
      completedAt: new Date(Date.parse(source.promptCreatedAt) + 1000).toISOString()
    });
    const manifest = await f.vault.getRunHistoryManifest(taskReference, { runId: sourceRunId });
    const modulePath = fileURLToPath(new URL("../src/encrypted-task-vault.js", import.meta.url));
    const script = `
      import { createEncryptedTaskVault } from ${JSON.stringify(modulePath)};
      const [filePath, encodedKey, taskReference, sourceRunId, targetRunId, threadId, digest] = process.argv.slice(1);
      const vault = createEncryptedTaskVault({
        filePath, key: Buffer.from(encodedKey, "base64")
      });
      const result = await vault.forkRunHistory(taskReference, {
        sourceRunId, targetRunId,
        expectedSourceThreadId: threadId,
        expectedSourceHistorySha256: digest
      });
      process.stdout.write(JSON.stringify({ duplicate: result.duplicate }));
    `;
    function forkInChild() {
      return new Promise((resolveChild, rejectChild) => {
        const child = spawn(process.execPath, [
          "--input-type=module", "-e", script,
          f.filePath, f.key.toString("base64"), taskReference,
          sourceRunId, targetRunId, manifest.threadId, manifest.historySha256
        ], { stdio: ["ignore", "pipe", "pipe"] });
        let stdout = "";
        let stderr = "";
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", chunk => { stdout += chunk; });
        child.stderr.on("data", chunk => { stderr += chunk; });
        child.once("error", rejectChild);
        child.once("close", code => {
          if (code !== 0) return rejectChild(new Error("Vault fork child exited " + code + ": " + stderr));
          try { resolveChild(JSON.parse(stdout)); }
          catch (error) { rejectChild(error); }
        });
      });
    }
    const results = await Promise.all([forkInChild(), forkInChild()]);
    assert.equal(results.filter(item => item.duplicate === false).length, 1);
    assert.equal(results.filter(item => item.duplicate === true).length, 1);
    const target = await f.vault.getTask(taskReference, { runId: targetRunId });
    assert.equal(target.history.length, 1);
    assert.equal(target.history[0].speaker, "ODIN");
    const sourceAfter = await f.vault.getTask(taskReference, { runId: sourceRunId });
    assert.equal(sourceAfter.history.length, 1);
    assert.equal(Object.hasOwn(sourceAfter.history[0], "origin"), false);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("only the configured read-only evidence gateway can mark a constraint VERIFIED", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-verified-constraint-"));
  await chmod(directory, 0o700);
  const filePath = join(directory, "tasks.vault");
  const key = Buffer.alloc(32, 13);
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const sourceReference = "github+commit://github.com/acme/project/" + sha + "?branch=main";
  const gateway = {
    async fetchCommit(repository, requestedSha) {
      return { repository, sha: requestedSha };
    },
    async branchContainsCommit(repository, branch, requestedSha) {
      return repository === "acme/project" && branch === "main" && requestedSha === sha;
    }
  };
  const vault = createEncryptedTaskVault({
    filePath, key, evidenceGateway: gateway,
    clock: () => new Date("2026-11-05T12:00:00.000Z")
  });
  const evaluation = {
    evaluated_on: "2026-11-05",
    maximum_evidence_age_days: 7,
    live_telemetry: false,
    unlisted_capability_decision: "RECHECK_REQUIRED"
  };
  const entry = {
    constraint_id: "verified-commit-backed-constraint",
    origin_system: "Repository evidence",
    origin_scope: "NINE_BUILDERS_GOVERNANCE",
    source_reference: sourceReference,
    observed_at: "2026-11-04T12:00:00.000Z",
    affected_capability: "Use a repository-backed governance rule",
    affected_operation: "Evaluate a constraint against its cited commit",
    force: "MANDATORY",
    operational_effect: "BLOCKS_OPERATION",
    responsible_builder: "KVASIR",
    decision_authority: "TYR",
    recheck_condition: "Recheck when the cited branch changes",
    decision_status: "SCOPED"
  };
  try {
    await vault.initializeTask("task:verified-constraint", "Verify a source-backed constraint");
    await assert.rejects(
      () => vault.appendOperationalConstraint("task:verified-constraint", {
        ...entry, evidence_state: "VERIFIED"
      }, evaluation),
      /only be written through the configured read-only evidence verifier/
    );
    const saved = await vault.verifyAndAppendOperationalConstraint(
      "task:verified-constraint", entry, evaluation
    );
    assert.equal(saved.durable, true);
    const task = await vault.getTask("task:verified-constraint", { runId: "run:verified-constraint" });
    const constraint = task.operationalLimitsSnapshot.constraints[0];
    assert.equal(constraint.evidence_state, "VERIFIED");
    assert.equal(constraint.evidenceVerification.verified, true);
    assert.equal(constraint.evidenceVerification.status, "VERIFIED");
    assert.equal(constraint.evidenceVerification.verificationMethod, "READ_ONLY_GITHUB_GATEWAY");
    assert.equal(constraint.evidenceVerification.objectId, sha);
    assert.equal(constraint.evidenceVerification.checkedAt, "2026-11-05T12:00:00.000Z");
    const noGatewayVault = createEncryptedTaskVault({ filePath, key });
    await assert.rejects(
      () => noGatewayVault.verifyAndAppendOperationalConstraint(
        "task:verified-constraint",
        { ...entry, constraint_id: "no-gateway-constraint" },
        evaluation
      ),
      /No trusted read-only evidence gateway is configured/
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("operational constraint register persists append-only entries across vault instances", async () => {
  const f = await fixture();
  try {
    await f.vault.initializeTask("task:constraints", "Assess environment and operation boundaries");
    const entry = {
      constraint_id: "chatgpt-automation-advisory-001",
      origin_system: "ChatGPT automation service",
      origin_scope: "CHATGPT_SERVICE",
      source_reference: "https://help.openai.com/",
      observed_at: "2026-11-04T12:00:00.000Z",
      affected_capability: "Create another scheduled ChatGPT task",
      affected_operation: "ChatGPT automation creation",
      force: "ADVISORY",
      evidence_state: "REPORTED",
      operational_effect: "NO_DIRECT_BLOCK",
      responsible_builder: "KVASIR",
      decision_authority: "USER",
      recheck_condition: "A task slot becomes available",
      decision_status: "SCOPED"
    };
    const evaluation = {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    };
    const first = await f.vault.appendOperationalConstraint("task:constraints", entry, evaluation);
    assert.equal(first.durable, true);
    assert.equal(first.duplicate, false);
    const duplicate = await f.vault.appendOperationalConstraint("task:constraints", entry, evaluation);
    assert.equal(duplicate.duplicate, true);
    const restartedVault = createEncryptedTaskVault({ filePath: f.filePath, key: f.key });
    const task = await restartedVault.getTask("task:constraints", { runId: "run:constraints-001" });
    assert.equal(task.operationalLimitsSnapshot.register_status, "LOADED");
    assert.deepEqual(task.operationalLimitsSnapshot.constraints, [entry]);
    assert.deepEqual(task.operationalLimitsSnapshot.runtime_evaluation, evaluation);
    await assert.rejects(
      () => restartedVault.appendOperationalConstraint("task:constraints", {
        ...entry, operational_effect: "BLOCKS_OPERATION"
      }, evaluation),
      /already exists with different content/
    );
    const reread = await restartedVault.getTask("task:constraints", { runId: "run:constraints-001" });
    assert.deepEqual(reread.operationalLimitsSnapshot.constraints, [entry]);
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

test("fork preserves normalized user-adoption authorization with its original source binding", async () => {
  const f = await correctionReadyFixture();
  try {
    const authorization = f.authorize();
    await f.vault.appendUserAdoption(f.taskReference, {
      runId: f.runId, correctionId: "correction-001", authorization
    });
    const source = await f.vault.getTask(f.taskReference, { runId: f.runId });
    const adoption = source.history.at(-1);
    const manifest = await f.vault.getRunHistoryManifest(f.taskReference, { runId: f.runId });
    const targetRunId = "run-adoption-continuation";
    await f.vault.forkRunHistory(f.taskReference, {
      sourceRunId: f.runId, targetRunId,
      expectedSourceThreadId: manifest.threadId,
      expectedSourceHistorySha256: manifest.historySha256
    });
    const target = await f.vault.getTask(f.taskReference, { runId: targetRunId });
    const importedAdoption = target.history.at(-1);
    assert.deepEqual(importedAdoption.userAuthorization, adoption.userAuthorization);
    assert.equal(importedAdoption.userAuthorization.runId, f.runId);
    assert.equal(importedAdoption.userAuthorization.threadId, source.threadId);
    assert.equal(importedAdoption.runId, targetRunId);
    assert.equal(importedAdoption.threadId, target.threadId);
    assert.deepEqual(importedAdoption.origin, {
      kind: "ENCRYPTED_VAULT_RUN_FORK",
      sourceTaskReference: f.taskReference,
      sourceRunId: f.runId,
      sourceThreadId: source.threadId,
      sourceTurnId: adoption.turnId
    });
    assert.deepEqual((await f.vault.getTask(f.taskReference, { runId: f.runId })).history, source.history);
  } finally {
    await rm(f.directory, { recursive: true, force: true });
  }
});

test("adoption fails closed without independent verification or a configured signing key", async () => {
  const incomplete = await correctionReadyFixture({ includeVerification: false });
  try {
    await assert.rejects(() => incomplete.vault.appendUserAdoption(incomplete.taskReference, {
      runId: incomplete.runId, correctionId: "correction-001", authorization: incomplete.authorize()
    }), /Only the latest independently verified Baldr correction can be adopted/);
  } finally {
    await rm(incomplete.directory, { recursive: true, force: true });
  }

  const complete = await correctionReadyFixture();
  try {
    const noKeyVault = createEncryptedTaskVault({
      filePath: complete.filePath, key: complete.key,
      clock: () => complete.fixedNow
    });
    await assert.rejects(() => noKeyVault.appendUserAdoption(complete.taskReference, {
      runId: complete.runId, correctionId: "correction-001", authorization: complete.authorize()
    }), /signing key is not configured/);
  } finally {
    await rm(complete.directory, { recursive: true, force: true });
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


test("preserves concurrent Builder-result appends to one run history across processes", async () => {
  const h = await fixture();
  try {
    await h.vault.initializeTask("task:concurrent-append", "Concurrent append test prompt.");
    await h.vault.getTask("task:concurrent-append", { runId: "run-shared" });
    const modulePath = fileURLToPath(new URL("../src/encrypted-task-vault.js", import.meta.url));
    const script = `
      import { createEncryptedTaskVault } from ${JSON.stringify(modulePath)};
      const [filePath, encodedKey, role, offset] = process.argv.slice(1);
      const vault = createEncryptedTaskVault({
        filePath, key: Buffer.from(encodedKey, "base64")
      });
      let completedAt = new Date(Date.now() + Number(offset)).toISOString();
      for (let attempt = 0; attempt < 5; attempt += 1) {
        const task = await vault.getTask("task:concurrent-append", { runId: "run-shared" });
        const record = {
          idempotencyKey: "run-shared:" + role,
          runId: "run-shared",
          threadId: task.threadId,
          role,
          requestedProviderId: "fixture-provider",
          requestedModel: role.toLowerCase() + "-fixture",
          providerReportedModel: role.toLowerCase() + "-fixture",
          responseSha256: (role === "ODIN" ? "c" : "d").repeat(64),
          reply: {
            body: role + " concurrent result",
            turn_type: "CONTRIBUTION",
            evidence_status: "PROPOSED"
          },
          completedAt
        };
        try {
          await vault.appendBuilderResult("task:concurrent-append", record);
          process.exit(0);
        } catch (error) {
          if (!/completion timestamp must increase strictly/.test(String(error?.message || error))) {
            throw error;
          }
          const latest = await vault.getTask("task:concurrent-append", { runId: "run-shared" });
          const last = latest.history.at(-1);
          const lastMillis = last
            ? (Number.isSafeInteger(last.timestampEpochMillis)
                ? last.timestampEpochMillis : Date.parse(last.completedAt))
            : Date.parse(latest.promptCreatedAt);
          completedAt = new Date(lastMillis + 1).toISOString();
        }
      }
      throw new Error("Concurrent append could not reconcile run-thread timestamp order");
    `;
    function appendInChild(role, offset) {
      return new Promise((resolveChild, rejectChild) => {
        const child = spawn(process.execPath, [
          "--input-type=module", "-e", script,
          h.filePath, h.key.toString("base64"), role, String(offset)
        ], { stdio: ["ignore", "ignore", "pipe"] });
        let stderr = "";
        child.stderr.setEncoding("utf8");
        child.stderr.on("data", chunk => { stderr += chunk; });
        child.once("error", rejectChild);
        child.once("close", code => {
          if (code === 0) resolveChild();
          else rejectChild(new Error("Vault append child exited " + code + ": " + stderr));
        });
      });
    }
    await Promise.all([
      appendInChild("ODIN", 1000),
      appendInChild("HODR", 2000)
    ]);
    const result = await h.vault.getTask("task:concurrent-append", { runId: "run-shared" });
    assert.equal(result.history.length, 2);
    assert.deepEqual(result.history.map(turn => turn.speaker).sort(), ["HODR", "ODIN"]);
    assert.equal(new Set(result.history.map(turn => turn.idempotencyKey)).size, 2);
    assert.ok(result.history[1].timestampEpochMillis > result.history[0].timestampEpochMillis);
  } finally {
    await rm(h.directory, { recursive: true, force: true });
  }
});


test("persists append-only Baldr invocation start and completion receipts in the encrypted vault", async () => {
  const h = await fixture();
  try {
    const taskReference = "task:baldr-receipt";
    const runId = "run-baldr-receipt";
    await h.vault.initializeTask(taskReference, "Review the eight Builder outcomes.");
    const task = await h.vault.getTask(taskReference, { runId });
    const reviewInput = '{"model":"baldr-model","messages":[]}';
    const inputManifest = {
      schema: "yggdrasil.baldr-review-manifest.v1",
      runId, taskReference, reviewerRole: "BALDR",
      peerOutcomeRefs: ["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"]
        .map(role => ({ role, status: "skipped", outcomeRef: null, errorCode: null })),
      requestedProviderId: "provider-baldr",
      requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
      requestedModelId: "baldr-model",
      requestBodySha256: sha256(reviewInput)
    };
    const start = {
      reviewInvocationId: "review-baldr-001",
      runId, taskReference, threadId: task.threadId,
      reviewerRole: "BALDR",
      inputManifestSha256: sha256(inputManifest),
      requestedProviderId: "provider-baldr",
      requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
      requestedModelId: "baldr-model",
      invokedAt: "2026-11-06T12:00:00.000Z",
      provenanceLevel: "ROUTE_SEPARATED",
      inputManifest, reviewInput
    };
    const started = await h.vault.beginBaldrReviewInvocation(taskReference, start);
    assert.equal(started.durable, true);
    await assert.rejects(
      () => h.vault.beginBaldrReviewInvocation(taskReference, start),
      /already in use/
    );
    await assert.rejects(
      () => h.vault.beginBaldrReviewInvocation(taskReference, {
        ...start, reviewInvocationId: "review-baldr-pending-2"
      }),
      /unknown outcome/
    );
    const responseSha256 = "b".repeat(64);
    const appendedTurn = await h.vault.appendBuilderResult(taskReference, {
      idempotencyKey: runId + ":BALDR", runId, threadId: task.threadId,
      role: "BALDR", requestedProviderId: start.requestedProviderId,
      requestedModel: start.requestedModelId, providerReportedModel: null,
      reviewInvocationId: start.reviewInvocationId,
      responseSha256,
      reply: {
        body: "Baldr review output.", turn_type: "CONTRIBUTION",
        evidence_status: "OBSERVED", recipient: null, reply_to_turn_id: null
      },
      completedAt: "2026-11-06T12:00:00.500Z"
    });
    const completion = {
      reviewInvocationId: start.reviewInvocationId,
      inputManifestSha256: start.inputManifestSha256,
      responseSha256,
      vaultTurnId: appendedTurn.checkpointId,
      providerRequestId: "req-123",
      provenanceLevel: "INVOCATION_ATTRIBUTED",
      status: "COMPLETED",
      completedAt: "2026-11-06T12:00:01.000Z"
    };
    const completed = await h.vault.completeBaldrReviewInvocation(taskReference, completion);
    assert.equal(completed.durable, true);
    const saved = await h.vault.getBaldrReviewInvocation(taskReference, {
      runId, reviewInvocationId: start.reviewInvocationId
    });
    assert.equal(saved.start.inputManifestSha256, start.inputManifestSha256);
    assert.equal(saved.start.reviewInput, reviewInput);
    assert.deepEqual(saved.start.inputManifest, inputManifest);
    assert.equal(saved.completion.vaultTurnId, completion.vaultTurnId);
    await assert.rejects(
      () => h.vault.completeBaldrReviewInvocation(taskReference, completion),
      /already completed/
    );
    const encryptedBytes = await readFile(h.filePath);
    assert.equal(encryptedBytes.includes(Buffer.from("INVOCATION_STARTED")), false);
    assert.equal(encryptedBytes.includes(Buffer.from("baldr-vault-turn-1")), false);
  } finally {
    await rm(h.directory, { recursive: true, force: true });
  }
});

test("does not permit a second Baldr invocation for a run after the first completes", async () => {
  const h = await fixture();
  try {
    const taskReference = "task:baldr-single-review";
    const runId = "run-baldr-single-review";
    await h.vault.initializeTask(taskReference, "Review once.");
    const task = await h.vault.getTask(taskReference, { runId });
    const reviewInput = '{"model":"baldr-model","messages":[]}';
    const inputManifest = {
      schema: "yggdrasil.baldr-review-manifest.v1",
      runId, taskReference, reviewerRole: "BALDR",
      peerOutcomeRefs: ["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"]
        .map(role => ({ role, status: "skipped", outcomeRef: null, errorCode: null })),
      requestedProviderId: "provider-baldr",
      requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
      requestedModelId: "baldr-model",
      requestBodySha256: sha256(reviewInput)
    };
    const start = {
      reviewInvocationId: "review-baldr-single",
      runId, taskReference, threadId: task.threadId,
      reviewerRole: "BALDR",
      inputManifestSha256: sha256(inputManifest),
      requestedProviderId: "provider-baldr",
      requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
      requestedModelId: "baldr-model",
      invokedAt: "2026-11-06T12:00:00.000Z",
      provenanceLevel: "ROUTE_SEPARATED",
      inputManifest, reviewInput
    };
    await h.vault.beginBaldrReviewInvocation(taskReference, start);
    const appendedTurn = await h.vault.appendBuilderResult(taskReference, {
      idempotencyKey: runId + ":BALDR", runId, threadId: task.threadId,
      role: "BALDR", requestedProviderId: start.requestedProviderId,
      requestedModel: start.requestedModelId, providerReportedModel: null,
      reviewInvocationId: start.reviewInvocationId,
      responseSha256: "d".repeat(64),
      reply: {
        body: "Baldr review output.", turn_type: "CONTRIBUTION",
        evidence_status: "OBSERVED", recipient: null, reply_to_turn_id: null
      },
      completedAt: "2026-11-06T12:00:00.500Z"
    });
    await h.vault.completeBaldrReviewInvocation(taskReference, {
      reviewInvocationId: start.reviewInvocationId,
      inputManifestSha256: start.inputManifestSha256,
      responseSha256: "d".repeat(64),
      vaultTurnId: appendedTurn.checkpointId,
      provenanceLevel: "INVOCATION_ATTRIBUTED",
      status: "COMPLETED",
      completedAt: "2026-11-06T12:00:01.000Z"
    });
    await assert.rejects(
      () => h.vault.beginBaldrReviewInvocation(taskReference, {
        ...start, reviewInvocationId: "review-baldr-single-second"
      }),
      /already exists for this run/
    );
  } finally {
    await rm(h.directory, { recursive: true, force: true });
  }
});


test("stores the Baldr invocation ID on the encrypted durable review turn", async () => {
  const h = await fixture();
  try {
    const taskReference = "task:baldr-turn-link";
    const runId = "run-baldr-turn-link";
    await h.vault.initializeTask(taskReference, "Review the peer outcomes.");
    const task = await h.vault.getTask(taskReference, { runId });
    const saved = await h.vault.appendBuilderResult(taskReference, {
      idempotencyKey: runId + ":BALDR",
      runId, threadId: task.threadId, role: "BALDR",
      requestedProviderId: "provider-baldr",
      requestedModel: "baldr-model",
      providerReportedModel: "reported-baldr-model",
      reviewInvocationId: "review-baldr-link-001",
      responseSha256: "e".repeat(64),
      reply: {
        body: "Review completed with evidence limitations.",
        turn_type: "CONTRIBUTION", evidence_status: "OBSERVED",
        recipient: null, reply_to_turn_id: null
      },
      completedAt: new Date(Date.now() + 1000).toISOString()
    });
    assert.equal(saved.durable, true);
    const reloaded = await h.vault.getTask(taskReference, { runId });
    assert.equal(reloaded.history[0].reviewInvocationId, "review-baldr-link-001");
  } finally {
    await rm(h.directory, { recursive: true, force: true });
  }
});
