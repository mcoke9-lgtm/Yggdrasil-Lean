import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import {
  serializeUserAdoptionPayload,
  verifyUserAdoptionAuthorization
} from "../src/user-adoption-authorization.js";

const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const publicPem = publicKey.export({ type: "spki", format: "pem" });
const now = Date.parse("2026-11-06T01:00:00.000Z");

function makeAssertion(overrides = {}) {
  const base = {
    version: 1,
    action: "ADOPT_CORRECTION",
    keyId: "user-action-key-2026-01",
    userId: "user-opaque-7f2a",
    taskReference: "task:pilot",
    runId: "builder:run-001",
    threadId: "nine-builders:builder:run-001",
    correctionId: "correction-001",
    nonce: "0123456789abcdefghijklmnopqrstuv",
    issuedAt: new Date(now).toISOString(),
    signature: ""
  };
  const assertion = { ...base, ...overrides };
  assertion.signature = sign(
    null, Buffer.from(serializeUserAdoptionPayload(assertion), "utf8"), privateKey
  ).toString("base64url");
  return assertion;
}

test("verifies a signed user action bound to one run, thread, and correction", () => {
  const assertion = makeAssertion();
  const result = verifyUserAdoptionAuthorization(assertion, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  });
  assert.deepEqual(
    [result.userId, result.runId, result.threadId, result.correctionId],
    ["user-opaque-7f2a", "builder:run-001", "nine-builders:builder:run-001", "correction-001"]
  );
  assert.equal(result.verificationMethod, "ED25519_USER_ACTION_ASSERTION");
  assert.equal(result.nonce, assertion.nonce);
  assert.match(result.signatureSha256, /^[a-f0-9]{64}$/);
});

test("signature binds every authorization field; tampering fails", () => {
  const assertion = makeAssertion();
  for (const field of ["userId", "runId", "threadId", "correctionId", "nonce", "issuedAt"]) {
    const changed = { ...assertion, [field]: assertion[field] + "-changed" };
    assert.throws(() => verifyUserAdoptionAuthorization(changed, {
      publicKeys: { "user-action-key-2026-01": publicPem }, now
    }), /signature is invalid/);
  }
});

test("rejects expired and future-dated assertions", () => {
  const expired = makeAssertion({ issuedAt: new Date(now - 300001).toISOString() });
  assert.throws(() => verifyUserAdoptionAuthorization(expired, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /expired or from the future/);
  const future = makeAssertion({ issuedAt: new Date(now + 30001).toISOString() });
  assert.throws(() => verifyUserAdoptionAuthorization(future, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /expired or from the future/);
});

test("rejects replay-unsafe malformed assertions and unexpected fields", () => {
  const badNonce = makeAssertion({ nonce: "short" });
  assert.throws(() => verifyUserAdoptionAuthorization(badNonce, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /nonce/);
  const extraField = { ...makeAssertion(), actor: "USER" };
  assert.throws(() => verifyUserAdoptionAuthorization(extraField, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /unexpected field set/);
});

test("requires configured Ed25519 key and valid signature encoding", () => {
  const assertion = makeAssertion();
  assert.throws(() => verifyUserAdoptionAuthorization(assertion, {
    publicKeys: {}, now
  }), /key is not configured/);
  const malformed = { ...assertion, signature: "not-a-signature" };
  assert.throws(() => verifyUserAdoptionAuthorization(malformed, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /canonical Ed25519 signature/);
  const wrongKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).publicKey
    .export({ type: "spki", format: "pem" });
  assert.throws(() => verifyUserAdoptionAuthorization(assertion, {
    publicKeys: { "user-action-key-2026-01": wrongKey }, now
  }), /must be Ed25519/);
});

test("rejects assertions not signed for correction adoption", () => {
  const assertion = makeAssertion({ action: "APPROVE_DEPLOYMENT" });
  assert.throws(() => verifyUserAdoptionAuthorization(assertion, {
    publicKeys: { "user-action-key-2026-01": publicPem }, now
  }), /version or action is unsupported/);
});
