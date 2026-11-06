import { createHash, randomUUID } from "node:crypto";

const SHA256 = /^[a-f0-9]{64}$/;
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/;
const PROVENANCE_LEVELS = new Set([
  "UNVERIFIED", "ROUTE_SEPARATED", "INVOCATION_ATTRIBUTED",
  "CONTROL_PLANE_DISTINCT", "INDEPENDENTLY_GOVERNED"
]);

export function sha256(value) {
  return createHash("sha256").update(
    typeof value === "string" ? value : JSON.stringify(value), "utf8"
  ).digest("hex");
}

export function createBaldrReviewManifest({
  runId, taskReference, promptTurnId, outcomes, requestBody,
  requestedProviderId, requestedEndpointIdentity, requestedModelId
} = {}) {
  if (typeof runId !== "string" || !SAFE_ID.test(runId) ||
      typeof taskReference !== "string" || !taskReference.trim() ||
      typeof promptTurnId !== "string" || !promptTurnId.trim() ||
      typeof requestBody !== "string" || !requestBody.trim() ||
      typeof requestedProviderId !== "string" || !requestedProviderId.trim() ||
      typeof requestedEndpointIdentity !== "string" || !requestedEndpointIdentity.trim() ||
      typeof requestedModelId !== "string" || !requestedModelId.trim() ||
      !Array.isArray(outcomes) || outcomes.length !== 8) {
    throw new TypeError("A complete Baldr review manifest input is required");
  }
  const peerOutcomeRefs = outcomes.map(item => {
    if (!item || !["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"].includes(item.role) ||
        !["succeeded", "failed", "skipped"].includes(item.status)) {
      throw new TypeError("Baldr peer outcome manifest is invalid");
    }
    if (item.status === "succeeded" &&
        (typeof item.outcomeRef !== "string" || !SAFE_ID.test(item.outcomeRef))) {
      throw new TypeError("Successful peer outcomes require durable references");
    }
    return {
      role: item.role, status: item.status,
      outcomeRef: item.status === "succeeded" ? item.outcomeRef : null,
      errorCode: item.status === "failed" ? item.errorCode ?? null : null
    };
  });
  const expected = ["ODIN", "HODR", "LOKI", "MUNINN", "HUGINN", "KVASIR", "MARI", "TYR"];
  if (peerOutcomeRefs.some((item, index) => item.role !== expected[index])) {
    throw new TypeError("Baldr peer outcomes must use canonical eight-role order");
  }
  const manifest = {
    schema: "yggdrasil.baldr-review-manifest.v1",
    runId, taskReference, reviewerRole: "BALDR", promptTurnId,
    peerOutcomeRefs,
    requestedProviderId, requestedEndpointIdentity, requestedModelId,
    requestBodySha256: sha256(requestBody)
  };
  return Object.freeze({
    manifest,
    inputManifestSha256: sha256(manifest)
  });
}

export function createBaldrInvocationStart({
  runId, taskReference, threadId, inputManifestSha256,
  requestedProviderId, requestedEndpointIdentity, requestedModelId,
  invokedAt, reviewInvocationId = randomUUID()
} = {}) {
  for (const [value, label] of [
    [runId, "runId"], [taskReference, "taskReference"], [threadId, "threadId"],
    [requestedProviderId, "requestedProviderId"],
    [requestedEndpointIdentity, "requestedEndpointIdentity"],
    [requestedModelId, "requestedModelId"], [reviewInvocationId, "reviewInvocationId"]
  ]) {
    if (typeof value !== "string" || !value.trim() || value.length > 512) {
      throw new TypeError(label + " is invalid");
    }
  }
  if (!SHA256.test(inputManifestSha256)) throw new TypeError("inputManifestSha256 must be SHA-256 hex");
  const date = new Date(invokedAt);
  if (!Number.isFinite(date.getTime()) || date.toISOString() !== invokedAt) {
    throw new TypeError("invokedAt must be canonical ISO-8601");
  }
  return Object.freeze({
    reviewInvocationId, runId, taskReference, threadId,
    reviewerRole: "BALDR", inputManifestSha256,
    requestedProviderId, requestedEndpointIdentity, requestedModelId,
    invokedAt, provenanceLevel: "ROUTE_SEPARATED"
  });
}

export function validateBaldrInvocationCompletion(start, completion) {
  if (!start || start.reviewerRole !== "BALDR" ||
      !completion || typeof completion !== "object" ||
      completion.reviewInvocationId !== start.reviewInvocationId ||
      completion.inputManifestSha256 !== start.inputManifestSha256 ||
      completion.status !== "COMPLETED" ||
      typeof completion.vaultTurnId !== "string" || !SAFE_ID.test(completion.vaultTurnId) ||
      typeof completion.responseSha256 !== "string" || !SHA256.test(completion.responseSha256) ||
      !PROVENANCE_LEVELS.has(completion.provenanceLevel) ||
      completion.provenanceLevel !== "INVOCATION_ATTRIBUTED") {
    throw new TypeError("Baldr invocation completion does not match its durable start");
  }
  const date = new Date(completion.completedAt);
  if (!Number.isFinite(date.getTime()) || date.toISOString() !== completion.completedAt ||
      date.getTime() < Date.parse(start.invokedAt)) {
    throw new TypeError("Baldr invocation completion timestamp is invalid");
  }
  if (completion.providerRequestId != null &&
      (typeof completion.providerRequestId !== "string" ||
       !/^[A-Za-z0-9._:-]{1,256}$/.test(completion.providerRequestId))) {
    throw new TypeError("providerRequestId is invalid");
  }
  return Object.freeze({
    reviewInvocationId: start.reviewInvocationId,
    inputManifestSha256: start.inputManifestSha256,
    responseSha256: completion.responseSha256,
    vaultTurnId: completion.vaultTurnId,
    provenanceLevel: completion.provenanceLevel,
    providerRequestId: completion.providerRequestId ?? null,
    status: "COMPLETED",
    completedAt: completion.completedAt
  });
}
