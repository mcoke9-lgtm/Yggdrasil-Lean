import { createHash, createPublicKey, verify as verifySignature } from "node:crypto";

const ASSERTION_FIELDS = [
  "version", "action", "keyId", "userId", "runId", "threadId",
  "correctionId", "nonce", "issuedAt", "signature"
];

/**
 * Canonical signed payload. Property order is fixed and excludes the signature.
 * The identity provider must sign this exact UTF-8 JSON string with Ed25519.
 */
export function serializeUserAdoptionPayload(assertion) {
  if (!assertion || typeof assertion !== "object" || Array.isArray(assertion)) {
    throw new TypeError("User adoption authorization assertion is required");
  }
  const payload = {
    version: assertion.version,
    action: assertion.action,
    keyId: assertion.keyId,
    userId: assertion.userId,
    runId: assertion.runId,
    threadId: assertion.threadId,
    correctionId: assertion.correctionId,
    nonce: assertion.nonce,
    issuedAt: assertion.issuedAt
  };
  return JSON.stringify(payload);
}

function required(value, label, max = 256) {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new TypeError(label + " must be a non-empty string no longer than " + max + " characters");
  }
  return value.trim();
}

/**
 * Verify a short-lived, one-use Ed25519 assertion from the authenticated user
 * action service. This proves the configured identity service authorized this
 * exact adoption action; it does not itself authenticate a UI session.
 */
export function verifyUserAdoptionAuthorization(assertion, {
  publicKeys,
  now = Date.now(),
  maxAgeMs = 300000,
  clockSkewMs = 30000
} = {}) {
  if (!assertion || typeof assertion !== "object" || Array.isArray(assertion)) {
    throw new TypeError("User adoption authorization assertion is required");
  }
  const keys = Object.keys(assertion).sort();
  if (keys.length !== ASSERTION_FIELDS.length ||
      keys.some((key, index) => key !== [...ASSERTION_FIELDS].sort()[index])) {
    throw new Error("User adoption authorization has an unexpected field set");
  }
  if (assertion.version !== 1 || assertion.action !== "ADOPT_CORRECTION") {
    throw new Error("User adoption authorization version or action is unsupported");
  }
  const keyId = required(assertion.keyId, "User authorization keyId", 64);
  const userId = required(assertion.userId, "Authenticated user ID", 256);
  const runId = required(assertion.runId, "Authorized run ID", 120);
  const threadId = required(assertion.threadId, "Authorized thread ID", 256);
  const correctionId = required(assertion.correctionId, "Authorized correction ID", 256);
  const nonce = required(assertion.nonce, "User action nonce", 128);
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(nonce)) {
    throw new Error("User action nonce must be 32–128 base64url characters");
  }
  const issuedAt = required(assertion.issuedAt, "User action issuedAt", 64);
  const issuedDate = new Date(issuedAt);
  if (!Number.isFinite(issuedDate.getTime()) || issuedDate.toISOString() !== issuedAt) {
    throw new Error("User action issuedAt must be canonical ISO-8601");
  }
  if (!Number.isSafeInteger(now) || !Number.isSafeInteger(maxAgeMs) ||
      maxAgeMs < 1000 || maxAgeMs > 900000 ||
      !Number.isSafeInteger(clockSkewMs) || clockSkewMs < 0 || clockSkewMs > 120000) {
    throw new RangeError("User authorization clock limits are invalid");
  }
  const age = now - issuedDate.getTime();
  if (age > maxAgeMs || age < -clockSkewMs) {
    throw new Error("User adoption authorization is expired or from the future");
  }
  const signatureText = required(assertion.signature, "User action signature", 256);
  const signature = Buffer.from(signatureText, "base64url");
  if (signature.length !== 64 || signature.toString("base64url") !== signatureText) {
    throw new Error("User action signature must be a canonical Ed25519 signature");
  }
  const pem = publicKeys?.[keyId];
  if (typeof pem !== "string" || !pem.trim()) {
    throw new Error("User authorization signing key is not configured");
  }
  const publicKey = createPublicKey(pem);
  if (publicKey.asymmetricKeyType !== "ed25519") {
    throw new Error("User authorization key must be Ed25519");
  }
  const payload = Buffer.from(serializeUserAdoptionPayload(assertion), "utf8");
  if (!verifySignature(null, payload, publicKey, signature)) {
    throw new Error("User adoption authorization signature is invalid");
  }
  return Object.freeze({
    version: 1,
    action: "ADOPT_CORRECTION",
    keyId,
    userId,
    runId,
    threadId,
    correctionId,
    nonce,
    issuedAt,
    issuedAtEpochMillis: issuedDate.getTime(),
    signatureSha256: createHash("sha256").update(signature).digest("hex"),
    verificationMethod: "ED25519_USER_ACTION_ASSERTION"
  });
}
