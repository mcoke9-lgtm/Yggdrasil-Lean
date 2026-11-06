import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, chmod, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BUILDER_ROLES } from "../src/run-ledger.js";
import {
  createBuilderExecutor,
  createOpenAiCompatibleExecutor,
  createOvernightDisclosureFingerprint
} from "../src/openai-compatible-executor.js";
import { createEncryptedTaskVault } from "../src/encrypted-task-vault.js";

function makeRoutes() {
  return Object.fromEntries(BUILDER_ROLES.map((role, index) => [role, {
    providerId: "provider-" + role.toLowerCase(),
    endpoint: "https://models.example.test/" + role.toLowerCase() + "/v1/chat/completions",
    model: "model-" + role.toLowerCase(),
    secretRef: "secret-" + role.toLowerCase()
  }]));
}

function makeReply(overrides = {}) {
  return {
    body: "A bounded, evidence-labelled contribution.",
    turn_type: "CONTRIBUTION",
    evidence_status: "PROPOSED",
    recipient: null,
    reply_to_turn_id: null,
    ...overrides
  };
}

function makeHarness({ routes = makeRoutes(), fetchImpl, taskVault, readSecret, timeoutMs } = {}) {
  const saved = [];
  const checkpoints = [];
  const runHistory = new Map();
  const vault = taskVault || {
    async getTask(reference, { runId }) {
      assert.equal(reference, "vault:task-001");
      const prompt = "Investigate the next safe engineering step.";
      const threadId = "thread-" + runId;
      if (!runHistory.has(runId)) {
        runHistory.set(runId, [{
          runId, threadId, checkpointId: "prior-user-turn", speaker: "USER",
          body: "Continue carefully."
        }]);
      }
      return {
        prompt,
        promptTurnId: "user-turn-001",
        threadId,
        history: [...runHistory.get(runId)],
        consent: {
          status: "USER_APPROVED",
          scope: "NINE_BUILDERS_OVERNIGHT",
          approvedAt: "2026-11-05T20:00:00.000Z",
          fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
        }
      };
    },
    async appendBuilderResult(reference, record) {
      assert.equal(reference, "vault:task-001");
      saved.push(record);
      runHistory.get(record.runId).push({
        runId: record.runId,
        threadId: record.threadId,
        checkpointId: "checkpoint-" + record.role.toLowerCase(),
        speaker: record.role,
        body: record.reply.body
      });
      return { durable: true, checkpointId: "checkpoint-" + record.role.toLowerCase() };
    }
  };
  const transport = fetchImpl || (async (_url, options) => {
    assert.equal(options.redirect, "error");
    assert.equal(options.method, "POST");
    return new Response(JSON.stringify({
      model: "provider-reported-model",
      choices: [{ message: { content: JSON.stringify(makeReply()) } }]
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  });
  const executor = createOpenAiCompatibleExecutor({
    routes,
    taskVault: vault,
    readSecret: readSecret || (async reference => "test-token-for-" + reference),
    fetchImpl: transport,
    timeoutMs: timeoutMs ?? 5000
  });
  return {
    executor, saved, checkpoints,
    context(role, outcomes = []) {
      return {
        runId: "builder:test-run",
        task: { taskReference: "vault:task-001" },
        outcomes,
        signal: new AbortController().signal,
        checkpoint: async value => { checkpoints.push({ role, value }); }
      };
    }
  };
}

function correctionReviewFixture(routes, reply, evidenceGateway) {
  const runId = "builder:correction-test";
  const threadId = "nine-builders:" + runId;
  const promptTurnId = "task-prompt-correction";
  const prompt = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident-1\nReview the recorded incident.";
  const incidentBody = JSON.stringify({
    incident_summary: "A test regression", observed_behavior: "Observed A",
    expected_behavior: "Expected B", evidence: "A failing test was recorded",
    unknowns: "No unknowns recorded", suspected_causes: "A mapping error",
    proposed_next_step: "Review the mapping", status: "OPEN_REVIEW"
  });
  const planBody = JSON.stringify({
    correction_id: "correction-1", incident_turn_id: "incident-1",
    objective: "Correct the mapping", acceptance_criteria: "The regression test passes",
    steps: "Update the mapping and rerun tests", owner: "HODR",
    rollback_condition: "Any regression appears", status: "PROPOSED"
  });
  const implementedBody = JSON.stringify({
    correction_id: "correction-1", incident_turn_id: "incident-1",
    stage: "IMPLEMENTED", evidence: "The code change is recorded",
    artifact_reference: "github+commit://github.com/acme/project/0123456789abcdef0123456789abcdef01234567?branch=correction",
    acceptance_criteria_result: "The implementation matches the intended change",
    unresolved: "Testing remains"
  });
  const makeTurn = (checkpointId, speaker, body, turnType, evidenceStatus, recipient, replyTo) => ({
    runId, threadId, checkpointId, speaker, body,
    reply: {
      body, turn_type: turnType, evidence_status: evidenceStatus,
      recipient, reply_to_turn_id: replyTo
    }
  });
  const history = [
    makeTurn("incident-1", "HODR", incidentBody, "IMPROVEMENT_INCIDENT", "OBSERVED", "USER", promptTurnId),
    makeTurn("plan-1", "TYR", planBody, "CORRECTION_PLAN", "PROPOSED", "USER", "incident-1"),
    makeTurn("implemented-1", "HODR", implementedBody, "CORRECTION_UPDATE", "IMPLEMENTED", "USER", "plan-1")
  ];
  const routesPrompt = createOvernightDisclosureFingerprint(routes, prompt);
  const saved = [];
  const checkpoints = [];
  const taskVault = {
    async getTask() {
      return {
        prompt, promptTurnId, threadId, history,
        consent: {
          status: "USER_APPROVED", scope: "NINE_BUILDERS_OVERNIGHT",
          approvedAt: "2026-11-05T20:00:00.000Z", fingerprint: routesPrompt
        }
      };
    },
    async appendBuilderResult(_reference, record) {
      saved.push(record);
      return { durable: true, checkpointId: "tested-correction-checkpoint" };
    }
  };
  const executor = createOpenAiCompatibleExecutor({
    routes, taskVault, evidenceGateway,
    readSecret: async () => "test-token",
    fetchImpl: async () => new Response(JSON.stringify({
      model: "test-model",
      choices: [{ message: { content: JSON.stringify(reply) } }]
    }), { status: 200 })
  });
  const context = {
    runId, task: { taskReference: "vault:task-001" },
    outcomes: [], signal: new AbortController().signal,
    checkpoint: async value => { checkpoints.push(value); }
  };
  return { executor, context, saved, checkpoints };
}

test("executor recovers a durable same-run result without repeating a provider request", async () => {
  const routes = makeRoutes();
  const runId = "builder:recover-existing";
  const threadId = "nine-builders:" + runId;
  const prompt = "Continue the saved Nine Builders task safely.";
  const body = JSON.stringify(makeReply());
  const completedAt = "2026-11-05T20:00:01.000Z";
  const responseSha256 = "a".repeat(64);
  let providerCalls = 0;
  let secretReads = 0;
  const checkpoints = [];
  const task = {
    prompt, promptTurnId: "user-turn-001", promptCreatedAt: "2026-11-05T20:00:00.000Z",
    threadId,
    history: [{
      protocolVersion: 4, runId, threadId,
      turnId: "checkpoint-hodr-001", checkpointId: "checkpoint-hodr-001",
      speaker: "HODR", body, type: "CONTRIBUTION",
      recipient: null, reply_to_turn_id: null, evidence_status: "PROPOSED",
      timestampEpochMillis: Date.parse(completedAt), completedAt,
      responseSha256, providerId: "provider-hodr", requestedModel: "model-hodr",
      providerReportedModel: "model-hodr",
      reply: {
        body, turn_type: "CONTRIBUTION", evidence_status: "PROPOSED",
        recipient: null, reply_to_turn_id: null
      }
    }],
    consent: {
      status: "USER_APPROVED", scope: "NINE_BUILDERS_OVERNIGHT",
      approvedAt: "2026-11-05T20:00:00.000Z",
      fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
    }
  };
  const executor = createOpenAiCompatibleExecutor({
    routes,
    taskVault: {
      async getTask(reference, options) {
        assert.equal(reference, "vault:task-001");
        assert.equal(options.runId, runId);
        return task;
      },
      async appendBuilderResult() { throw new Error("A recovered result must not be appended again"); }
    },
    readSecret: async () => { secretReads += 1; return "unused"; },
    fetchImpl: async () => { providerCalls += 1; throw new Error("Provider must not be called during recovery"); }
  });
  const context = {
    runId, task: { taskReference: "vault:task-001" }, outcomes: [],
    signal: new AbortController().signal,
    checkpoint: async value => { checkpoints.push(value); }
  };
  const result = await executor("HODR", context);
  assert.equal(result.status, "succeeded");
  assert.match(result.summary, /no model request was repeated/);
  assert.equal(providerCalls, 0);
  assert.equal(secretReads, 0);
  assert.equal(checkpoints.length, 1);
  assert.equal(checkpoints[0].safeToResume, true);
  const probe = await executor.recoverDurableResult("HODR", context);
  assert.equal(probe.checkpointId, "checkpoint-hodr-001");
  assert.equal(probe.status, "succeeded");
  assert.equal(probe.data.responseSha256, responseSha256);
  assert.equal(providerCalls, 0);
});

test("uses operator routes, secure task lookup, bounded JSON output, and durable checkpoints", async () => {
  let request;
  const h = makeHarness({
    fetchImpl: async (url, options) => {
      request = { url, options, body: JSON.parse(options.body) };
      return new Response(JSON.stringify({
        model: "reported-odin-v2",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  const result = await h.executor("ODIN", h.context("ODIN"));
  assert.equal(result.status, "succeeded");
  assert.match(result.summary, /checkpoint-odin/);
  assert.match(request.url, /odin\/v1\/chat\/completions$/);
  assert.equal(request.options.headers.Authorization, "Bearer test-token-for-secret-odin");
  assert.equal(request.options.redirect, "error");
  assert.equal(request.body.model, "model-odin");
  assert.equal(request.body.max_tokens, 2048);
  assert.deepEqual(request.body.response_format, { type: "json_object" });
  assert.match(request.body.messages[0].content, /Odin/);
  assert.match(request.body.messages[0].content, /coordination, orchestration, and integration/);
  const userContext = JSON.parse(request.body.messages[1].content);
  assert.match(userContext.task_prompt, /next safe engineering step/);
  assert.equal(userContext.operational_limits_snapshot.register_status, "UNAVAILABLE");
  assert.equal(userContext.operational_limits_snapshot.live_telemetry, false);
  assert.equal(userContext.prior_turns_oldest_first.length, 1);
  assert.equal(h.saved.length, 1);
  assert.equal(h.saved[0].idempotencyKey, "builder:test-run:ODIN");
  assert.equal(h.saved[0].threadId, "thread-builder:test-run");
  assert.equal(h.checkpoints[0].value.threadId, "thread-builder:test-run");
  assert.equal(h.saved[0].providerReportedModel, "reported-odin-v2");
  assert.equal(h.saved[0].requestedProviderId, "provider-odin");
  assert.equal(h.saved[0].reply.body, "A bounded, evidence-labelled contribution.");
  assert.equal(JSON.stringify(h.saved[0]).includes("test-token"), false);
  assert.equal(h.checkpoints.length, 1);
  assert.equal(h.checkpoints[0].value.safeToResume, true);
  assert.equal(h.checkpoints[0].value.checkpointId, "checkpoint-odin");
});

test("invokes all nine canonical roles with distinct responsibilities and Baldr last", async () => {
  const prompts = [];
  const h = makeHarness({
    fetchImpl: async (_url, options) => {
      prompts.push(JSON.parse(options.body));
      return new Response(JSON.stringify({
        model: "reported-role-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  const outcomes = [];
  for (const role of BUILDER_ROLES) {
    await h.executor(role, h.context(role, [...outcomes]));
    outcomes.push({ role, status: "succeeded", summary: role + " completed" });
  }
  assert.deepEqual(h.saved.map(item => item.role), BUILDER_ROLES);
  assert.equal(prompts.length, 9);
  assert.match(prompts[0].messages[0].content, /coordination, orchestration, and integration/);
  assert.match(prompts[1].messages[0].content, /service runner, GitHub monitoring/);
  assert.match(prompts[2].messages[0].content, /daylight-saving handling, and duplicate suppression/);
  assert.match(prompts[3].messages[0].content, /persistence, history, continuity/);
  assert.match(prompts[4].messages[0].content, /evidence, source verification/);
  assert.match(prompts[5].messages[0].content, /operational constraints, capability limits/);
  assert.match(prompts[5].messages[0].content, /originating system, exact scope/);
  assert.match(prompts[5].messages[0].content, /Distinguish a ChatGPT\/provider\/host-specific limit from a Nine Builders governance rule/);
  assert.match(prompts[5].messages[0].content, /never promote an external recommendation or service-scoped quota into a collective prohibition/);
  assert.match(prompts[5].messages[0].content, /binding legal, safety, security, consent, access-control/);
  assert.match(prompts[6].messages[0].content, /complementary alternatives, relationships/);
  assert.match(prompts[7].messages[0].content, /authorization, permissions, and security/);
  assert.match(prompts[8].messages[0].content, /separately invoked final reviewer/);
  const baldrContext = JSON.parse(prompts[8].messages[1].content);
  assert.equal(baldrContext.prior_builder_outcomes.length, 8);
});

test("passes through a schema-valid operational limits snapshot", async () => {
  const routes = makeRoutes();
  const prompt = "Kvasir assesses an advisory ChatGPT service recommendation";
  const snapshot = {
    schema: "yggdrasil.operational-capacity-register",
    schema_version: "1.0.0",
    register_status: "LOADED",
    constraints: [{
      constraint_id: "chatgpt-automation-quota",
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
    }],
    runtime_evaluation: {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    }
  };
  let userContext;
  let systemInstruction;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [],
          operationalLimitsSnapshot: snapshot,
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "limits-checkpoint" }; }
    },
    fetchImpl: async (_url, options) => {
      const messages = JSON.parse(options.body).messages;
      systemInstruction = messages[0].content;
      userContext = JSON.parse(messages[1].content);
      return new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await h.executor("KVASIR", h.context("KVASIR"));
  assert.equal(userContext.operational_limits_snapshot.register_status, "LOADED");
  assert.equal(userContext.operational_limits_snapshot.runtime_evaluation.live_telemetry, false);
  assert.equal(userContext.operational_limits_snapshot.constraints[0].origin_scope, "CHATGPT_SERVICE");
  assert.equal(userContext.operational_limits_snapshot.constraints[0].force, "ADVISORY");
  assert.equal(userContext.operational_limits_snapshot.constraints[0].operational_effect, "NO_DIRECT_BLOCK");
  assert.match(systemInstruction, /BLOCKED means do not retry or recommend only the exact operation in the recorded origin scope/);
  assert.match(systemInstruction, /An ADVISORY constraint with NO_DIRECT_BLOCK is not a prohibition/);
  assert.match(systemInstruction, /never promote an external recommendation or service-scoped quota into a collective prohibition/);
});

test("loads the persisted encrypted Constraint Register into Kvasir runtime context", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-runtime-register-"));
  await chmod(directory, 0o700);
  const filePath = join(directory, "tasks.vault");
  const verifiedSha = "0123456789abcdef0123456789abcdef01234567";
  const verifiedReference = "github+commit://github.com/acme/project/" + verifiedSha + "?branch=main";
  const vault = createEncryptedTaskVault({
    filePath,
    key: Buffer.alloc(32, 11),
    evidenceGateway: {
      async fetchCommit(repository, sha) { return { repository, sha }; },
      async branchContainsCommit(repository, branch, sha) {
        return repository === "acme/project" && branch === "main" && sha === verifiedSha;
      }
    },
    clock: () => new Date("2026-11-05T12:00:00.000Z")
  });
  const routes = makeRoutes();
  const prompt = "Investigate the next safe engineering step.";
  let sentContext;
  try {
    await vault.initializeTask("vault:task-001", prompt);
    await vault.approveTask(
      "vault:task-001",
      createOvernightDisclosureFingerprint(routes, prompt),
      "2026-11-05T20:00:00.000Z"
    );
    const entry = {
      constraint_id: "chatgpt-service-advisory-runtime-001",
      origin_system: "ChatGPT automation service",
      origin_scope: "CHATGPT_SERVICE",
      source_reference: verifiedReference,
      observed_at: "2026-11-04T12:00:00.000Z",
      affected_capability: "Create another scheduled ChatGPT task",
      affected_operation: "ChatGPT automation creation",
      force: "ADVISORY",
      operational_effect: "NO_DIRECT_BLOCK",
      responsible_builder: "KVASIR",
      decision_authority: "USER",
      recheck_condition: "Recheck when automation capacity changes",
      decision_status: "SCOPED"
    };
    await vault.verifyAndAppendOperationalConstraint("vault:task-001", entry, {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    });
    const h = makeHarness({
      routes,
      taskVault: vault,
      fetchImpl: async (_url, options) => {
        sentContext = JSON.parse(options.body).messages;
        return new Response(JSON.stringify({
          model: "reported-model",
          choices: [{ message: { content: JSON.stringify(makeReply()) } }]
        }), { status: 200 });
      }
    });
    await h.executor("KVASIR", h.context("KVASIR"));
    const context = JSON.parse(sentContext[1].content);
    assert.equal(context.operational_limits_snapshot.register_status, "LOADED");
    assert.equal(context.operational_limits_snapshot.constraints[0].evidence_state, "VERIFIED");
    assert.equal(context.operational_limits_snapshot.constraints[0].evidenceVerification.objectId, verifiedSha);
    assert.match(sentContext[0].content, /ADVISORY constraint with NO_DIRECT_BLOCK is not a prohibition/);
    assert.match(sentContext[0].content, /exact operation in the recorded origin scope/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("preserves a mandatory Nine Builders authorization boundary for Kvasir", async () => {
  const routes = makeRoutes();
  const prompt = "Kvasir assesses a mandatory authorization boundary";
  const snapshot = {
    schema: "yggdrasil.operational-capacity-register",
    schema_version: "1.0.0",
    register_status: "LOADED",
    constraints: [{
      constraint_id: "unattended-run-user-authorization",
      origin_system: "Nine Builders governance",
      origin_scope: "NINE_BUILDERS_GOVERNANCE",
      source_reference: "https://github.com/mcoke9-lgtm/Yggdrasil-Lean/issues/39",
      observed_at: "2026-11-04T12:00:00.000Z",
      affected_capability: "Start an unattended overnight run",
      affected_operation: "Activate an unattended run without explicit user approval",
      force: "MANDATORY",
      evidence_state: "REPORTED",
      operational_effect: "BLOCKS_OPERATION",
      responsible_builder: "KVASIR",
      decision_authority: "TYR",
      recheck_condition: "Obtain explicit user authorization for the specified run",
      decision_status: "SCOPED"
    }],
    runtime_evaluation: {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    }
  };
  let userContext;
  let systemInstruction;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [],
          operationalLimitsSnapshot: snapshot,
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "authorization-boundary-checkpoint" }; }
    },
    fetchImpl: async (_url, options) => {
      const messages = JSON.parse(options.body).messages;
      systemInstruction = messages[0].content;
      userContext = JSON.parse(messages[1].content);
      return new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await h.executor("KVASIR", h.context("KVASIR"));
  const constraint = userContext.operational_limits_snapshot.constraints[0];
  assert.equal(userContext.operational_limits_snapshot.register_status, "LOADED");
  assert.equal(constraint.origin_scope, "NINE_BUILDERS_GOVERNANCE");
  assert.equal(constraint.force, "MANDATORY");
  assert.equal(constraint.evidence_state, "REPORTED");
  assert.equal(constraint.operational_effect, "BLOCKS_OPERATION");
  assert.equal(constraint.affected_operation, "Activate an unattended run without explicit user approval");
  assert.equal(constraint.decision_authority, "TYR");
  assert.match(systemInstruction, /BLOCKED means do not retry or recommend only the exact operation in the recorded origin scope/);
  assert.match(systemInstruction, /never bypass a binding legal, safety, security, consent, access-control, contractual, or authorization boundary/);
  assert.match(systemInstruction, /the authority Týr must check/);
});

test("does not accept a caller-asserted VERIFIED constraint without source verification", async () => {
  const routes = makeRoutes();
  const prompt = "Check evidence status without overstating verification";
  const snapshot = {
    schema: "yggdrasil.operational-capacity-register",
    schema_version: "1.0.0",
    register_status: "LOADED",
    constraints: [{
      constraint_id: "unsupported-verified-claim",
      origin_system: "External service",
      origin_scope: "CHATGPT_SERVICE",
      source_reference: "https://example.test/policy",
      observed_at: "2026-11-04T12:00:00.000Z",
      affected_capability: "Create scheduled tasks",
      affected_operation: "Create a scheduled ChatGPT task",
      force: "ADVISORY",
      evidence_state: "VERIFIED",
      operational_effect: "NO_DIRECT_BLOCK",
      responsible_builder: "KVASIR",
      decision_authority: "USER",
      recheck_condition: "Recheck the source through an independent verifier",
      decision_status: "UNASSESSED"
    }],
    runtime_evaluation: {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    }
  };
  let sentContext;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt, threadId: "thread-" + runId, history: [],
          operationalLimitsSnapshot: snapshot,
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "unverified-source-checkpoint" }; }
    },
    fetchImpl: async (_url, options) => {
      sentContext = JSON.parse(options.body).messages;
      return new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await h.executor("KVASIR", h.context("KVASIR"));
  const context = JSON.parse(sentContext[1].content);
  assert.equal(context.operational_limits_snapshot.register_status, "UNAVAILABLE");
  assert.match(context.operational_limits_snapshot.reason, /schema, constraint-entry, or freshness checks/);
});

test("blocks provider disclosure when the task vault has no explicit user approval", async () => {
  let calls = 0;
  let secretReads = 0;
  const h = makeHarness({
    taskVault: {
      async getTask() { return { prompt: "unapproved task", history: [] }; },
      async appendBuilderResult() { throw new Error("must not persist"); }
    },
    readSecret: async () => { secretReads++; return "token"; },
    fetchImpl: async () => { calls++; return new Response("{}"); }
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /no explicit user approval/);
  assert.equal(secretReads, 0);
  assert.equal(calls, 0);
});

test("rejects a task vault that changes the dedicated thread during one run", async () => {
  const routes = makeRoutes();
  const prompt = "same run prompt";
  let threadNumber = 0;
  let appends = 0;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        const threadId = "thread-" + (++threadNumber);
        return {
          prompt,
          threadId,
          history: [],
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult(_reference, record) {
        appends++;
        return { durable: true, checkpointId: "checkpoint-" + record.role };
      }
    }
  });
  await h.executor("ODIN", h.context("ODIN"));
  await assert.rejects(() => h.executor("HODR", h.context("HODR")), /changed the dedicated thread ID/);
  assert.equal(appends, 1);
});

test("falls back to unavailable when a constraint entry is incomplete or stale", async () => {
  for (const constraint of [
    {
      constraint_id: "missing-operation",
      origin_system: "Test service",
      origin_scope: "PROVIDER_SERVICE",
      source_reference: "https://example.invalid/source",
      observed_at: "2026-11-05T12:00:00.000Z",
      affected_capability: "Test capability",
      force: "MANDATORY",
      evidence_state: "REPORTED",
      operational_effect: "BLOCKS_OPERATION",
      responsible_builder: "KVASIR",
      decision_authority: "TYR",
      recheck_condition: "Recheck the provider"
    },
    {
      constraint_id: "stale-evidence",
      origin_system: "Test service",
      origin_scope: "PROVIDER_SERVICE",
      source_reference: "https://example.invalid/source",
      observed_at: "2026-10-01T12:00:00.000Z",
      affected_capability: "Test capability",
      affected_operation: "Test operation",
      force: "MANDATORY",
      evidence_state: "REPORTED",
      operational_effect: "BLOCKS_OPERATION",
      responsible_builder: "KVASIR",
      decision_authority: "TYR",
      recheck_condition: "Recheck the provider"
    }
  ]) {
    const routes = makeRoutes();
    const prompt = "task with an invalid constraint entry";
    let userContext;
    const h = makeHarness({
      routes,
      taskVault: {
        async getTask(_reference, { runId }) {
          return {
            prompt, threadId: "thread-" + runId, history: [],
            operationalLimitsSnapshot: {
              schema: "yggdrasil.operational-capacity-register",
              schema_version: "1.0.0", register_status: "LOADED",
              constraints: [constraint],
              runtime_evaluation: {
                evaluated_on: "2026-11-05", maximum_evidence_age_days: 7,
                live_telemetry: false, unlisted_capability_decision: "RECHECK_REQUIRED"
              }
            },
            consent: {
              status: "USER_APPROVED", scope: "NINE_BUILDERS_OVERNIGHT",
              approvedAt: "2026-11-05T20:00:00.000Z",
              fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
            }
          };
        },
        async appendBuilderResult() { return { durable: true, checkpointId: "invalid-entry-fallback" }; }
      },
      fetchImpl: async (_url, options) => {
        userContext = JSON.parse(JSON.parse(options.body).messages[1].content);
        return new Response(JSON.stringify({
          model: "reported-model",
          choices: [{ message: { content: JSON.stringify(makeReply()) } }]
        }), { status: 200 });
      }
    });
    await h.executor("ODIN", h.context("ODIN"));
    assert.equal(userContext.operational_limits_snapshot.register_status, "UNAVAILABLE");
    assert.equal(userContext.operational_limits_snapshot.unlisted_capability_decision, "RECHECK_REQUIRED");
  }
});

test("rejects a VERIFIED source proof older than the register evidence-age limit", async () => {
  const routes = makeRoutes();
  const prompt = "Kvasir evaluates the freshness of a verified source proof";
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const sourceReference = "github+commit://github.com/acme/project/" + sha + "?branch=main";
  const snapshot = {
    schema: "yggdrasil.operational-capacity-register",
    schema_version: "1.0.0",
    register_status: "LOADED",
    constraints: [{
      constraint_id: "stale-verified-source",
      origin_system: "Repository evidence",
      origin_scope: "NINE_BUILDERS_GOVERNANCE",
      source_reference: sourceReference,
      observed_at: "2026-11-04T12:00:00.000Z",
      affected_capability: "Evaluate a source-backed governance constraint",
      affected_operation: "Apply the referenced governance constraint",
      force: "MANDATORY",
      evidence_state: "VERIFIED",
      evidenceVerification: {
        verified: true,
        status: "VERIFIED",
        verificationMethod: "READ_ONLY_GITHUB_GATEWAY",
        kind: "COMMIT",
        repository: "acme/project",
        objectId: sha,
        branch: "main",
        workflow: null,
        expectedSha: null,
        checkedAt: "2026-10-20T12:00:00.000Z"
      },
      operational_effect: "BLOCKS_OPERATION",
      responsible_builder: "KVASIR",
      decision_authority: "TYR",
      recheck_condition: "Recheck the referenced source",
      decision_status: "SCOPED"
    }],
    runtime_evaluation: {
      evaluated_on: "2026-11-05",
      maximum_evidence_age_days: 7,
      live_telemetry: false,
      unlisted_capability_decision: "RECHECK_REQUIRED"
    }
  };
  let userContext;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [],
          operationalLimitsSnapshot: snapshot,
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "stale-proof-checkpoint" }; }
    },
    fetchImpl: async (_url, options) => {
      userContext = JSON.parse(JSON.parse(options.body).messages[1].content);
      return new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await h.executor("KVASIR", h.context("KVASIR"));
  assert.equal(userContext.operational_limits_snapshot.register_status, "UNAVAILABLE");
  assert.match(userContext.operational_limits_snapshot.reason, /freshness checks/);
});

test("falls back to unavailable when a supplied operational limits snapshot is malformed", async () => {
  const routes = makeRoutes();
  const prompt = "task with malformed limits";
  let userContext;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [],
          operationalLimitsSnapshot: {
            schema: "yggdrasil.operational-capacity-register",
            schema_version: "1.0.0",
            register_status: "LOADED",
            constraints: [],
            runtime_evaluation: {
              evaluated_on: "2026-11-05",
              maximum_evidence_age_days: 7,
              live_telemetry: true,
              unlisted_capability_decision: "PROCEED"
            }
          },
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "safe-fallback" }; }
    },
    fetchImpl: async (_url, options) => {
      userContext = JSON.parse(JSON.parse(options.body).messages[1].content);
      return new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await h.executor("ODIN", h.context("ODIN"));
  assert.equal(userContext.operational_limits_snapshot.register_status, "UNAVAILABLE");
  assert.equal(userContext.operational_limits_snapshot.live_telemetry, false);
  assert.equal(userContext.operational_limits_snapshot.unlisted_capability_decision, "RECHECK_REQUIRED");
});

test("rejects history that does not belong to the current run-specific thread", async () => {
  const routes = makeRoutes();
  const prompt = "approved task prompt";
  let calls = 0;
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [{ runId: "previous-run", threadId: "old-thread", speaker: "ODIN", body: "old data" }],
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { throw new Error("must not persist"); }
    },
    fetchImpl: async () => { calls++; return new Response("{}"); }
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /outside this scheduled run/);
  assert.equal(calls, 0);
});

test("blocks disclosure when the approved prompt or any route changes", async () => {
  const approvedRoutes = makeRoutes();
  const changedRoutes = makeRoutes();
  changedRoutes.ODIN.model = "odin-model-after-approval";
  const prompt = "approved task prompt";
  let calls = 0;
  const h = makeHarness({
    routes: changedRoutes,
    taskVault: {
      async getTask() {
        return {
          prompt,
          history: [],
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(approvedRoutes, prompt)
          }
        };
      },
      async appendBuilderResult() { throw new Error("must not persist"); }
    },
    fetchImpl: async () => { calls++; return new Response("{}"); }
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /changed after user approval/);
  assert.equal(calls, 0);

  const promptRoutes = makeRoutes();
  const approvedPrompt = "original prompt";
  const changedPrompt = "edited prompt";
  const promptChanged = makeHarness({
    routes: promptRoutes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt: changedPrompt,
          threadId: "thread-" + runId,
          history: [],
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(promptRoutes, approvedPrompt)
          }
        };
      },
      async appendBuilderResult() { throw new Error("must not persist"); }
    },
    fetchImpl: async () => { calls++; return new Response("{}"); }
  });
  await assert.rejects(() => promptChanged.executor("ODIN", promptChanged.context("ODIN")), /changed after user approval/);
  assert.equal(calls, 0);
});

test("returns a durable interruption outcome for explicit stop events", async () => {
  const cases = [
    {
      type: "INTERRUPTION",
      body: {
        stop_state: "PAUSE",
        observed_constraint: "Required source evidence is unavailable",
        source_time: "2026-11-05T20:00:00Z",
        affected_step: "provider route validation",
        impact: "Continuing would rely on unknown capability state",
        unknowns: "current route availability",
        feasible_alternative: "pause and verify the route",
        required_recheck_authorization: "fresh route evidence",
        resume_condition: "route evidence is verified"
      }
    },
    {
      type: "IMPROVEMENT_INCIDENT",
      body: {
        incident_summary: "Provider returned an invalid reply",
        observed_behavior: "response did not match the contract",
        expected_behavior: "strict Builder JSON response",
        evidence: "provider response rejected by parser",
        unknowns: "whether the provider changed its output format",
        suspected_causes: "model or endpoint behavior changed",
        proposed_next_step: "independent review",
        status: "OPEN_REVIEW"
      }
    }
  ];
  for (const item of cases) {
    const h = makeHarness({
      fetchImpl: async () => new Response(JSON.stringify({
        model: "reported-model",
        choices: [{ message: { content: JSON.stringify(makeReply({
          body: JSON.stringify(item.body),
          turn_type: item.type,
          evidence_status: "OBSERVED",
          recipient: "USER",
          reply_to_turn_id: "user-turn-001"
        })) } }]
      }), { status: 200 })
    });
    const result = await h.executor("HODR", h.context("HODR"));
    assert.equal(result.status, "interrupted");
    assert.equal(result.turnType, item.type);
    assert.equal(h.saved.length, 1, "stop event must be saved before the round pauses");
    assert.equal(h.checkpoints.length, 1);
    assert.equal(h.checkpoints[0].value.safeToResume, false);
  }
});

test("Baldr cannot run until all eight other Builder outcomes and their recorded results are present", async () => {
  let calls = 0;
  const routes = makeRoutes();
  const prompt = "Review all peer outputs.";
  const outcomes = BUILDER_ROLES.filter(role => role !== "BALDR")
    .map(role => ({ role, status: "succeeded", summary: role + " checkpointed" }));
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        const threadId = "thread-" + runId;
        return {
          prompt,
          promptTurnId: "user-turn-001",
          threadId,
          history: outcomes.map(item => ({
            runId, threadId, checkpointId: "peer-" + item.role,
            speaker: item.role, body: item.summary
          })),
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { durable: true, checkpointId: "baldr-checkpoint" }; }
    },
    fetchImpl: async () => {
      calls++;
      return new Response(JSON.stringify({
        model: "reported-baldr",
        choices: [{ message: { content: JSON.stringify(makeReply()) } }]
      }), { status: 200 });
    }
  });
  await assert.rejects(() => h.executor("BALDR", h.context("BALDR", [])), /all eight other roles/);
  assert.equal(calls, 0);
  await h.executor("BALDR", h.context("BALDR", outcomes));
  assert.equal(calls, 1);
});

test("requires unique model IDs and a separate Baldr endpoint and model", () => {
  const duplicateModel = makeRoutes();
  duplicateModel.MARI.model = duplicateModel.ODIN.model;
  assert.throws(() => makeHarness({ routes: duplicateModel }), /Model ID must be unique/);

  const sharedBaldrEndpoint = makeRoutes();
  sharedBaldrEndpoint.BALDR.endpoint = sharedBaldrEndpoint.ODIN.endpoint;
  assert.throws(() => makeHarness({ routes: sharedBaldrEndpoint }), /Baldr endpoint and model/);

  const sharedBaldrModel = makeRoutes();
  sharedBaldrModel.BALDR.model = sharedBaldrModel.ODIN.model;
  assert.throws(() => makeHarness({ routes: sharedBaldrModel }), /Model ID must be unique/);
});

test("rejects non-HTTPS provider endpoints except loopback development endpoints", () => {
  const insecure = makeRoutes();
  insecure.ODIN.endpoint = "http://models.example.test/v1/chat/completions";
  assert.throws(() => makeHarness({ routes: insecure }), /HTTPS/);

  const local = makeRoutes();
  local.ODIN.endpoint = "http://localhost:1234/v1/chat/completions";
  assert.doesNotThrow(() => makeHarness({ routes: local }));
});

test("cancellation aborts an in-flight provider request and does not persist a result", async () => {
  const controller = new AbortController();
  let markStarted;
  const started = new Promise(resolve => { markStarted = resolve; });
  const h = makeHarness({
    fetchImpl: async (_url, options) => {
      markStarted();
      return new Promise((_resolve, reject) => {
        if (options.signal.aborted) {
          reject(options.signal.reason);
          return;
        }
        options.signal.addEventListener("abort", () => reject(options.signal.reason), { once: true });
      });
    }
  });
  const context = h.context("ODIN");
  context.signal = controller.signal;
  const running = h.executor("ODIN", context);
  await started;
  controller.abort(new Error("user stopped run"));
  await assert.rejects(running, /user stopped run/);
  assert.equal(h.saved.length, 0);
  assert.equal(h.checkpoints.length, 0);
});

test("does not expose provider error bodies or secrets in errors", async () => {
  const h = makeHarness({
    fetchImpl: async () => new Response("private provider response body", { status: 401 })
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), error => {
    assert.match(error.message, /HTTP 401/);
    assert.doesNotMatch(error.message, /private provider response body/);
    assert.doesNotMatch(error.message, /test-token/);
    return true;
  });
  assert.equal(h.saved.length, 0);
});

test("rejects malformed or unauthorized Builder response claims", async () => {
  const h = makeHarness({
    fetchImpl: async () => new Response(JSON.stringify({
      model: "reported-odin",
      choices: [{ message: { content: JSON.stringify(makeReply({ evidence_status: "ADOPTED" })) } }]
    }), { status: 200 })
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /unsupported evidence_status/);
  assert.equal(h.saved.length, 0);
});

test("loads the host executor only from owner-protected config and operator modules", async () => {
  const directory = await mkdtemp(join(tmpdir(), "nine-builders-host-executor-"));
  const configPath = join(directory, "routes.json");
  const taskVaultPath = join(directory, "task-vault.mjs");
  const credentialsPath = join(directory, "credentials.mjs");
  const evidenceGatewayPath = join(directory, "evidence-gateway.mjs");
  const names = [
    "BUILDER_HOST_CONFIG_FILE", "BUILDER_TASK_VAULT_MODULE",
    "BUILDER_CREDENTIALS_MODULE", "BUILDER_EVIDENCE_GATEWAY_MODULE"
  ];
  const previous = Object.fromEntries(names.map(name => [name, process.env[name]]));
  try {
    await writeFile(configPath, JSON.stringify({ version: 1, routes: makeRoutes(), evidenceGatewaySecretRef: "github-evidence-read-only" }), { mode: 0o600 });
    await writeFile(taskVaultPath,
      'export async function createTaskVault({ evidenceGateway }) {\n' +
      '  if (!evidenceGateway || typeof evidenceGateway.fetchCommit !== "function" || typeof evidenceGateway.fetchWorkflowJobs !== "function") throw new Error("vault did not receive the configured evidence gateway");\n' +
      '  return {\n' +
      '    async getTask() { return { prompt: "host task" }; },\n' +
      '    async appendBuilderResult() { return { durable: true, checkpointId: "host-checkpoint" }; }\n' +
      '  };\n' +
      '}\n', { mode: 0o600 });
    await writeFile(credentialsPath, [
      'let calls = 0;',
      'export async function readSecret() { calls += 1; if (calls > 1) throw new Error("unexpected secret read"); return "host-secret"; }',
      ''
    ].join("\n"), { mode: 0o600 });
    await writeFile(evidenceGatewayPath, [
      'export async function createEvidenceGateway({ readSecret, secretRef }) {',
      '  if (typeof readSecret !== "function" || secretRef !== "github-evidence-read-only") throw new Error("invalid gateway factory inputs");',
      '  await readSecret(secretRef);',
      '  return {',
      '    async fetchCommit() { return null; },',
      '    async branchContainsCommit() { return false; },',
      '    async fetchWorkflowRun() { return null; },',
      '    async fetchWorkflowJobs() { return []; }',
      '  };',
      '}',
      ''
    ].join("\n"), { mode: 0o600 });
    process.env.BUILDER_HOST_CONFIG_FILE = configPath;
    process.env.BUILDER_TASK_VAULT_MODULE = taskVaultPath;
    process.env.BUILDER_CREDENTIALS_MODULE = credentialsPath;
    process.env.BUILDER_EVIDENCE_GATEWAY_MODULE = evidenceGatewayPath;

    if (process.platform !== "win32") {
      await chmod(configPath, 0o644);
      await assert.rejects(() => createBuilderExecutor(), /Builder host config permissions are too broad/);
      await chmod(configPath, 0o600);
      const configMode = (await stat(configPath)).mode & 0o777;
      assert.equal(configMode, 0o600, `Config fixture mode after restoration: 0o${configMode.toString(8)}`);

      await chmod(credentialsPath, 0o644);
      await assert.rejects(() => createBuilderExecutor(), /Credentials module permissions are too broad/);
      await chmod(credentialsPath, 0o600);
      const credentialsMode = (await stat(credentialsPath)).mode & 0o777;
      assert.equal(credentialsMode, 0o600, `Credentials fixture mode after restoration: 0o${credentialsMode.toString(8)}`);

      await chmod(taskVaultPath, 0o644);
      await assert.rejects(() => createBuilderExecutor(), /Task vault module permissions are too broad/);
      await chmod(taskVaultPath, 0o600);
      const taskVaultMode = (await stat(taskVaultPath)).mode & 0o777;
      assert.equal(taskVaultMode, 0o600, `Task vault fixture mode after restoration: 0o${taskVaultMode.toString(8)}`);
    }
    const executor = await createBuilderExecutor();
    assert.equal(typeof executor, "function");

    const legacyTaskVaultPath = join(directory, "legacy-task-vault.mjs");
    await writeFile(legacyTaskVaultPath, [
      'export async function getTask() { return { prompt: "legacy host task" }; }',
      'export async function appendBuilderResult() { return { durable: true, checkpointId: "legacy-checkpoint" }; }',
      ''
    ].join("\n"), { mode: 0o600 });
    process.env.BUILDER_TASK_VAULT_MODULE = legacyTaskVaultPath;
    await assert.rejects(
      () => createBuilderExecutor(),
      /must export createTaskVault.*when a GitHub evidence gateway is configured/
    );
  } finally {
    for (const name of names) {
      if (previous[name] === undefined) delete process.env[name];
      else process.env[name] = previous[name];
    }
    await rm(directory, { recursive: true, force: true });
  }
});

test("correction updates fail closed when no evidence gateway is configured", async () => {
  const routes = makeRoutes();
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const reply = makeReply({
    body: JSON.stringify({
      correction_id: "correction-1", incident_turn_id: "incident-1",
      stage: "TESTED", evidence: "The scheduler tests completed",
      artifact_reference: "github+actions://github.com/acme/project/runs/12345?workflow=scheduler-tests&sha=" + sha,
      acceptance_criteria_result: "The regression test passed",
      unresolved: "No unresolved test failures"
    }),
    turn_type: "CORRECTION_UPDATE", evidence_status: "TESTED",
    recipient: "USER", reply_to_turn_id: "implemented-1"
  });
  const h = correctionReviewFixture(routes, reply, null);
  await assert.rejects(() => h.executor("HUGINN", h.context),
    /Correction evidence verification UNAVAILABLE/);
  assert.equal(h.saved.length, 0);
  assert.equal(h.checkpoints.length, 0);
});

test("correction updates persist only after the read-only gateway verifies the exact successful run", async () => {
  const routes = makeRoutes();
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const reply = makeReply({
    body: JSON.stringify({
      correction_id: "correction-1", incident_turn_id: "incident-1",
      stage: "TESTED", evidence: "The scheduler tests completed",
      artifact_reference: "github+actions://github.com/acme/project/runs/12345?workflow=scheduler-tests&sha=" + sha,
      acceptance_criteria_result: "The regression test passed",
      unresolved: "No unresolved test failures"
    }),
    turn_type: "CORRECTION_UPDATE", evidence_status: "TESTED",
    recipient: "USER", reply_to_turn_id: "implemented-1"
  });
  const gateway = {
    async fetchWorkflowRun(repository, runId) {
      return {
        repository, runId, workflow: "scheduler-tests", headSha: sha,
        status: "completed", conclusion: "success"
      };
    },
    async fetchWorkflowJobs() {
      return [{ status: "completed", conclusion: "success" }];
    }
  };
  const h = correctionReviewFixture(routes, reply, gateway);
  const result = await h.executor("HUGINN", h.context);
  assert.equal(result.status, "succeeded");
  assert.equal(h.saved.length, 1);
  assert.equal(h.saved[0].evidenceVerification.verified, true);
  assert.equal(h.saved[0].evidenceVerification.status, "VERIFIED");
  assert.equal(h.saved[0].evidenceVerification.verificationMethod, "READ_ONLY_GITHUB_GATEWAY");
  assert.equal(h.saved[0].evidenceVerification.workflow, "scheduler-tests");
  assert.equal(h.saved[0].evidenceVerification.verificationMethod, "READ_ONLY_GITHUB_GATEWAY");
  assert.equal(h.saved[0].evidenceVerification.expectedSha, sha);
  assert.ok(Number.isFinite(Date.parse(h.saved[0].evidenceVerification.checkedAt)));
  assert.equal(h.checkpoints.length, 1);
});

test("rejects a task vault that does not confirm durable result persistence", async () => {
  const routes = makeRoutes();
  const prompt = "test task";
  const h = makeHarness({
    routes,
    taskVault: {
      async getTask(_reference, { runId }) {
        return {
          prompt,
          threadId: "thread-" + runId,
          history: [],
          consent: {
            status: "USER_APPROVED",
            scope: "NINE_BUILDERS_OVERNIGHT",
            approvedAt: "2026-11-05T20:00:00.000Z",
            fingerprint: createOvernightDisclosureFingerprint(routes, prompt)
          }
        };
      },
      async appendBuilderResult() { return { checkpointId: "not-durable" }; }
    }
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /did not confirm durable persistence/);
  assert.equal(h.checkpoints.length, 0);
});
