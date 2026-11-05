import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, chmod, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BUILDER_ROLES } from "../src/run-ledger.js";
import {
  createBuilderExecutor,
  createOpenAiCompatibleExecutor,
  createOvernightDisclosureFingerprint
} from "../src/openai-compatible-executor.js";

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
  const vault = taskVault || {
    async getTask(reference, { runId }) {
      assert.equal(reference, "vault:task-001");
      const prompt = "Investigate the next safe engineering step.";
      const threadId = "thread-" + runId;
      return {
        prompt,
        threadId,
        history: [{ runId, threadId, speaker: "USER", body: "Continue carefully." }],
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
  assert.equal(h.checkpoints[0].value.safeToResume, false);
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
  assert.match(prompts[6].messages[0].content, /complementary alternatives, relationships/);
  assert.match(prompts[7].messages[0].content, /authorization, permissions, and security/);
  assert.match(prompts[8].messages[0].content, /separately invoked final reviewer/);
  const baldrContext = JSON.parse(prompts[8].messages[1].content);
  assert.equal(baldrContext.prior_builder_outcomes.length, 8);
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
});

test("Baldr cannot run until all eight other Builder outcomes are present", async () => {
  let calls = 0;
  const h = makeHarness({ fetchImpl: async () => {
    calls++;
    return new Response(JSON.stringify({
      model: "reported-baldr",
      choices: [{ message: { content: JSON.stringify(makeReply()) } }]
    }), { status: 200 });
  }});
  await assert.rejects(() => h.executor("BALDR", h.context("BALDR", [])), /all eight other roles/);
  assert.equal(calls, 0);

  const outcomes = BUILDER_ROLES.filter(role => role !== "BALDR")
    .map(role => ({ role, status: "succeeded", summary: role + " checkpointed" }));
  await h.executor("BALDR", h.context("BALDR", outcomes));
  assert.equal(calls, 1);
  assert.match(h.saved[0].reply.body, /contribution/);
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
  const names = ["BUILDER_HOST_CONFIG_FILE", "BUILDER_TASK_VAULT_MODULE", "BUILDER_CREDENTIALS_MODULE"];
  const previous = Object.fromEntries(names.map(name => [name, process.env[name]]));
  try {
    await writeFile(configPath, JSON.stringify({ version: 1, routes: makeRoutes() }), { mode: 0o600 });
    await writeFile(taskVaultPath,
      'export async function getTask() { return { prompt: "host task" }; }\n' +
      'export async function appendBuilderResult() { return { durable: true, checkpointId: "host-checkpoint" }; }\n');
    await writeFile(credentialsPath,
      'export async function readSecret() { return "host-secret"; }\n');
    process.env.BUILDER_HOST_CONFIG_FILE = configPath;
    process.env.BUILDER_TASK_VAULT_MODULE = taskVaultPath;
    process.env.BUILDER_CREDENTIALS_MODULE = credentialsPath;

    if (process.platform !== "win32") {
      await chmod(configPath, 0o644);
      await assert.rejects(() => createBuilderExecutor(), /permissions are too broad/);
      await chmod(configPath, 0o600);
    }
    const executor = await createBuilderExecutor();
    assert.equal(typeof executor, "function");
  } finally {
    for (const name of names) {
      if (previous[name] === undefined) delete process.env[name];
      else process.env[name] = previous[name];
    }
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects a task vault that does not confirm durable result persistence", async () => {
  const h = makeHarness({
    taskVault: {
      async getTask() { return { prompt: "test task" }; },
      async appendBuilderResult() { return { checkpointId: "not-durable" }; }
    }
  });
  await assert.rejects(() => h.executor("ODIN", h.context("ODIN")), /did not confirm durable persistence/);
  assert.equal(h.checkpoints.length, 0);
});
