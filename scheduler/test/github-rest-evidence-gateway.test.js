import test from "node:test";
import assert from "node:assert/strict";
import { createEvidenceGateway } from "../src/github-rest-evidence-gateway.js";

const sha = "0123456789abcdef0123456789abcdef01234567";

test("REST gateway reads commits and verifies branch ancestry using GET only", async () => {
  const calls = [];
  let secretReads = 0;
  const gateway = await createEvidenceGateway({
    secretRef: "github-evidence-read-only",
    readSecret: async reference => {
      secretReads += 1;
      assert.equal(reference, "github-evidence-read-only");
      return "temporary-test-token";
    },
    fetchImpl: async (url, options) => {
      calls.push({ url: String(url), options });
      if (String(url).endsWith("/commits/" + sha)) {
        return new Response(JSON.stringify({
          sha, html_url: "https://github.com/acme/project/commit/" + sha
        }), { status: 200 });
      }
      if (String(url).includes("/compare/")) {
        return new Response(JSON.stringify({
          status: "ahead", merge_base_commit: { sha }
        }), { status: 200 });
      }
      throw new Error("Unexpected endpoint");
    }
  });
  const signal = new AbortController().signal;
  assert.deepEqual(await gateway.fetchCommit("acme/project", sha, signal), {
    repository: "acme/project", sha
  });
  assert.equal(await gateway.branchContainsCommit("acme/project", "feature/recovery", sha, signal), true);
  assert.equal(secretReads, 1);
  assert.equal(Object.isFrozen(gateway), true);
  assert.deepEqual(Object.keys(gateway).sort(), [
    "branchContainsCommit", "fetchCommit", "fetchWorkflowJobs", "fetchWorkflowRun"
  ]);
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.options.method, "GET");
    assert.equal(call.options.redirect, "error");
    assert.equal(call.options.headers.Authorization, "Bearer temporary-test-token");
    assert.equal(call.options.signal, signal);
  }
  assert.match(calls[1].url, /compare\//);
});

test("REST gateway reads exact workflow identity and paginates every job", async () => {
  const urls = [];
  const gateway = await createEvidenceGateway({
    secretRef: "github-evidence-read-only",
    readSecret: async () => "temporary-test-token",
    fetchImpl: async (url, options) => {
      urls.push(String(url));
      assert.equal(options.method, "GET");
      if (String(url).endsWith("/actions/runs/12345")) {
        return new Response(JSON.stringify({
          id: 12345, name: "scheduler-tests", head_sha: sha,
          status: "completed", conclusion: "success",
          repository: { full_name: "acme/project" }
        }), { status: 200 });
      }
      if (String(url).includes("&page=1")) {
        return new Response(JSON.stringify({
          total_count: 101,
          jobs: Array.from({ length: 100 }, () => ({ status: "completed", conclusion: "success" }))
        }), { status: 200 });
      }
      if (String(url).includes("&page=2")) {
        return new Response(JSON.stringify({
          total_count: 101,
          jobs: [{ status: "completed", conclusion: "success" }]
        }), { status: 200 });
      }
      throw new Error("Unexpected endpoint");
    }
  });
  const run = await gateway.fetchWorkflowRun("acme/project", 12345);
  assert.deepEqual(run, {
    repository: "acme/project", runId: 12345, workflow: "scheduler-tests",
    headSha: sha, status: "completed", conclusion: "success"
  });
  const jobs = await gateway.fetchWorkflowJobs("acme/project", 12345);
  assert.equal(jobs.length, 101);
  assert.equal(urls.some(url => url.includes("page=1")), true);
  assert.equal(urls.some(url => url.includes("page=2")), true);
});

test("REST gateway rejects malformed repository identities and missing secret references", async () => {
  await assert.rejects(() => createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: " "
  }), /secretRef/);
  const gateway = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only"
  });
  await assert.rejects(() => gateway.fetchCommit("acme/project/extra", sha), /owner\/name/);
});


test("retries idempotent GitHub GET requests on transient 5xx with bounded backoff", async () => {
  let calls = 0;
  const gateway = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 3, baseDelayMs: 0, maxDelayMs: 50,
    fetchImpl: async () => {
      calls += 1;
      if (calls < 3) return new Response("temporary", { status: 503 });
      return new Response(JSON.stringify({
        sha, html_url: "https://github.com/acme/project/commit/" + sha
      }), { status: 200 });
    }
  });
  assert.deepEqual(await gateway.fetchCommit("acme/project", sha), {
    repository: "acme/project", sha
  });
  assert.equal(calls, 3);
});

test("honors a short Retry-After for rate limits but does not retry permission failures", async () => {
  let rateLimitCalls = 0;
  const rateLimited = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 2, baseDelayMs: 0, maxDelayMs: 100,
    fetchImpl: async () => {
      rateLimitCalls += 1;
      if (rateLimitCalls === 1) {
        return new Response("rate limited", {
          status: 429, headers: { "Retry-After": "0" }
        });
      }
      return new Response(JSON.stringify({
        sha, html_url: "https://github.com/acme/project/commit/" + sha
      }), { status: 200 });
    }
  });
  assert.equal((await rateLimited.fetchCommit("acme/project", sha)).sha, sha);
  assert.equal(rateLimitCalls, 2);

  let forbiddenCalls = 0;
  const forbidden = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    fetchImpl: async () => {
      forbiddenCalls += 1;
      return new Response("forbidden", { status: 403 });
    }
  });
  await assert.rejects(() => forbidden.fetchCommit("acme/project", sha), error => {
    assert.equal(error.name, "GitHubEvidenceHttpError");
    assert.equal(error.status, 403);
    return true;
  });
  assert.equal(forbiddenCalls, 1);
});

test("bounds Retry-After and the total retry budget", async () => {
  let calls = 0;
  const tooLong = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 3, maxDelayMs: 5,
    fetchImpl: async () => new Response("rate limited", {
      status: 429, headers: { "Retry-After": "10" }
    })
  });
  await assert.rejects(() => tooLong.fetchCommit("acme/project", sha), error =>
    error.name === "GitHubEvidenceRetryDelayExceededError"
  );

  const exhausted = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 2, baseDelayMs: 0, maxDelayMs: 5,
    fetchImpl: async () => {
      calls += 1;
      return new Response("unavailable", { status: 503 });
    }
  });
  await assert.rejects(() => exhausted.fetchCommit("acme/project", sha), error =>
    error.name === "GitHubEvidenceHttpError" && error.status === 503
  );
  assert.equal(calls, 2);
});

test("retries safe transport errors and observes cancellation during backoff", async () => {
  let calls = 0;
  const retrying = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 2, baseDelayMs: 0, maxDelayMs: 10,
    fetchImpl: async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("temporary network failure");
      return new Response(JSON.stringify({
        sha, html_url: "https://github.com/acme/project/commit/" + sha
      }), { status: 200 });
    }
  });
  assert.equal((await retrying.fetchCommit("acme/project", sha)).sha, sha);
  assert.equal(calls, 2);

  const controller = new AbortController();
  const waiting = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    maxAttempts: 3, maxDelayMs: 2000,
    fetchImpl: async () => new Response("rate limited", {
      status: 429, headers: { "Retry-After": "1" }
    })
  });
  const pending = waiting.fetchCommit("acme/project", sha, controller.signal);
  setTimeout(() => controller.abort(new Error("cancelled")), 10);
  await assert.rejects(() => pending, /cancelled/);
});

test("REST gateway maps HTTP failures to a non-secret error type", async () => {
  const gateway = await createEvidenceGateway({
    readSecret: async () => "token",
    secretRef: "read-only",
    fetchImpl: async () => new Response("forbidden", { status: 403 })
  });
  await assert.rejects(() => gateway.fetchCommit("acme/project", sha), error => {
    assert.equal(error.name, "GitHubEvidenceHttpError");
    assert.equal(error.message.includes("token"), false);
    return true;
  });
});
