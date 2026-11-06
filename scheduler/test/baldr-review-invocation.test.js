import test from "node:test";
import assert from "node:assert/strict";
import {
  createBaldrReviewManifest, createBaldrInvocationStart,
  validateBaldrInvocationCompletion
} from "../src/baldr-review-invocation.js";

const roles = ["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"];
const outcomes = roles.map((role, index) => ({
  role, status: index === 2 ? "failed" : "succeeded",
  outcomeRef: index === 2 ? null : role.toLowerCase() + "-turn",
  errorCode: index === 2 ? "EXECUTOR_FAILURE" : null
}));
const base = {
  runId: "builder:test-run", taskReference: "task:opaque-1",
  promptTurnId: "prompt-1", outcomes, requestBody: '{"model":"baldr"}',
  requestedProviderId: "provider-z",
  requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
  requestedModelId: "baldr-model"
};

test("creates a reproducible, route-bound Baldr review manifest", () => {
  const a = createBaldrReviewManifest(base);
  const b = createBaldrReviewManifest(base);
  assert.equal(a.inputManifestSha256, b.inputManifestSha256);
  assert.equal(a.manifest.peerOutcomeRefs.length, 8);
  assert.equal(a.manifest.peerOutcomeRefs[2].status, "failed");
  assert.equal(a.manifest.peerOutcomeRefs[2].outcomeRef, null);
  assert.notEqual(
    a.inputManifestSha256,
    createBaldrReviewManifest({ ...base, requestedProviderId: "provider-other" }).inputManifestSha256
  );
  assert.notEqual(
    a.inputManifestSha256,
    createBaldrReviewManifest({ ...base, requestBody: '{"model":"changed"}' }).inputManifestSha256
  );
});

test("rejects incomplete, reordered, or non-durable peer outcome packages", () => {
  assert.throws(() => createBaldrReviewManifest({ ...base, outcomes: outcomes.slice(1) }));
  assert.throws(() => createBaldrReviewManifest({
    ...base, outcomes: [outcomes[1], outcomes[0], ...outcomes.slice(2)]
  }));
  assert.throws(() => createBaldrReviewManifest({
    ...base, outcomes: outcomes.map((item, index) =>
      index === 0 ? { ...item, outcomeRef: null } : item
    )
  }));
});

test("accepts only a completion receipt linked to its exact invocation and durable turn", () => {
  const start = createBaldrInvocationStart({
    runId: base.runId, taskReference: base.taskReference, threadId: "thread-1",
    inputManifestSha256: "a".repeat(64),
    requestedProviderId: "provider-z",
    requestedEndpointIdentity: "https://example.invalid/v1/chat/completions",
    requestedModelId: "baldr-model",
    invokedAt: "2026-11-06T12:00:00.000Z",
    reviewInvocationId: "review-001"
  });
  const completion = {
    reviewInvocationId: "review-001",
    inputManifestSha256: "a".repeat(64),
    responseSha256: "b".repeat(64),
    vaultTurnId: "vault-turn-1",
    providerRequestId: "req-123",
    provenanceLevel: "INVOCATION_ATTRIBUTED",
    status: "COMPLETED",
    completedAt: "2026-11-06T12:00:01.000Z"
  };
  assert.equal(validateBaldrInvocationCompletion(start, completion).vaultTurnId, "vault-turn-1");
  assert.throws(() => validateBaldrInvocationCompletion(start, {
    ...completion, inputManifestSha256: "c".repeat(64)
  }));
  assert.throws(() => validateBaldrInvocationCompletion(start, {
    ...completion, provenanceLevel: "INDEPENDENTLY_GOVERNED"
  }));
  assert.throws(() => validateBaldrInvocationCompletion(start, {
    ...completion, completedAt: "2026-11-06T11:59:59.000Z"
  }));
});
