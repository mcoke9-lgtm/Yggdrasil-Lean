import test from "node:test";
import assert from "node:assert/strict";
import {
  parseBuilderEvidenceReference,
  verifyBuilderEvidenceReference
} from "../src/builder-evidence-reference.js";

const sha = "0123456789abcdef0123456789abcdef01234567";
const commitRef = "github+commit://github.com/acme/project/" + sha + "?branch=correction";
const actionsRef = "github+actions://github.com/acme/project/runs/12345?workflow=scheduler-tests&sha=" + sha;

test("parses a typed full-SHA commit reference", () => {
  assert.deepEqual(parseBuilderEvidenceReference(commitRef), {
    kind: "COMMIT", repository: "acme/project", objectId: sha,
    branch: "correction", workflow: null, expectedSha: null
  });
});

test("parses a typed Actions run reference with exact workflow and SHA", () => {
  assert.deepEqual(parseBuilderEvidenceReference(actionsRef), {
    kind: "ACTIONS_RUN", repository: "acme/project", objectId: "12345",
    branch: null, workflow: "scheduler-tests", expectedSha: sha
  });
});

test("rejects malformed, duplicate, short, or non-GitHub references", () => {
  assert.throws(() => parseBuilderEvidenceReference("github+commit://github.com/acme/project/abc?branch=main"));
  assert.throws(() => parseBuilderEvidenceReference(
    "github+commit://github.com/acme/project/" + sha + "?branch=main&branch=other"
  ));
  assert.throws(() => parseBuilderEvidenceReference(
    "github+actions://github.com/acme/project/runs/12345?workflow=tests&sha=abc"
  ));
  assert.throws(() => parseBuilderEvidenceReference(
    "github+commit://evil.example/acme/project/" + sha + "?branch=main"
  ));
});

test("commit verification confirms exact repository, SHA, and branch membership", async () => {
  const gateway = {
    async fetchCommit(repository, objectId) {
      assert.equal(repository, "acme/project");
      assert.equal(objectId, sha);
      return { repository, sha };
    },
    async branchContainsCommit(repository, branch, objectId) {
      assert.deepEqual([repository, branch, objectId], ["acme/project", "correction", sha]);
      return true;
    }
  };
  const result = await verifyBuilderEvidenceReference(commitRef, gateway);
  assert.equal(result.verified, true);
  assert.equal(result.status, "VERIFIED");
  assert.match(result.reason, /branch membership confirmed/);
});

test("commit verification fails closed when the branch does not contain the commit", async () => {
  const gateway = {
    async fetchCommit(repository, objectId) { return { repository, sha: objectId }; },
    async branchContainsCommit() { return false; }
  };
  const result = await verifyBuilderEvidenceReference(commitRef, gateway);
  assert.equal(result.verified, false);
  assert.equal(result.status, "UNVERIFIED");
});

test("Actions verification requires the exact successful run and all successful jobs", async () => {
  const gateway = {
    async fetchWorkflowRun(repository, runId) {
      return {
        repository, runId, workflow: "scheduler-tests",
        headSha: sha, status: "completed", conclusion: "success"
      };
    },
    async fetchWorkflowJobs() {
      return [
        { status: "completed", conclusion: "success" },
        { status: "completed", conclusion: "success" }
      ];
    }
  };
  const result = await verifyBuilderEvidenceReference(actionsRef, gateway);
  assert.equal(result.verified, true);
  assert.equal(result.status, "VERIFIED");
});

test("Actions verification rejects partial success, skipped, or mismatched runs", async () => {
  const gateway = {
    async fetchWorkflowRun(repository, runId) {
      return {
        repository, runId, workflow: "scheduler-tests",
        headSha: sha, status: "completed", conclusion: "success"
      };
    },
    async fetchWorkflowJobs() {
      return [
        { status: "completed", conclusion: "success" },
        { status: "completed", conclusion: "failure" }
      ];
    }
  };
  const result = await verifyBuilderEvidenceReference(actionsRef, gateway);
  assert.equal(result.verified, false);
  assert.equal(result.status, "UNVERIFIED");
  assert.match(result.reason, /At least one workflow job/);
});

test("missing or incomplete gateways report UNAVAILABLE, never VERIFIED", async () => {
  assert.equal((await verifyBuilderEvidenceReference(commitRef, null)).status, "UNAVAILABLE");
  assert.equal((await verifyBuilderEvidenceReference(commitRef, {
    async fetchCommit() { return null; }
  })).status, "UNAVAILABLE");
});

test("transport failures report UNAVAILABLE without exposing error details", async () => {
  const gateway = {
    async fetchCommit() { const error = new Error("secret-bearing transport detail"); error.name = "FetchError"; throw error; },
    async branchContainsCommit() { return true; }
  };
  const result = await verifyBuilderEvidenceReference(commitRef, gateway);
  assert.equal(result.status, "UNAVAILABLE");
  assert.equal(result.reason, "Evidence source unavailable: FetchError");
  assert.equal(result.reason.includes("secret-bearing"), false);
});
