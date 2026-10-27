import { createHash } from "node:crypto";

/**
 * Builds a deterministic, storage-neutral graph-input certificate.
 *
 * This binds the supplied records to a vertex/edge payload; it cannot prove
 * that the supplied records exhaust the intended source scope. Completeness
 * therefore remains an explicit external attestation.
 */
export function buildGraphInputCertificate(records, {
  sourceReadId,
  sourceDigest,
  sourceCompletenessVerified = false,
} = {}) {
  const findings = [];
  if (typeof sourceReadId !== "string" || sourceReadId.length === 0 ||
      sourceReadId.trim() !== sourceReadId) {
    findings.push({ code: "MISSING_OR_MALFORMED_SOURCE_READ_ID" });
  }
  if (typeof sourceDigest !== "string" || sourceDigest.length === 0 ||
      sourceDigest.trim() !== sourceDigest) {
    findings.push({ code: "MISSING_OR_MALFORMED_SOURCE_DIGEST" });
  }
  if (typeof sourceCompletenessVerified !== "boolean") {
    findings.push({ code: "MALFORMED_COMPLETENESS_ATTESTATION" });
  }

  const counts = new Map();
  for (const record of records) {
    const id = record?.recordId;
    if (typeof id !== "string" || id.length === 0 || id.trim() !== id) {
      findings.push({ code: "MALFORMED_RECORD_ID", recordId: id ?? null });
      continue;
    }
    counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  const duplicateRecordIds = [...counts].filter(([, count]) => count > 1)
    .map(([recordId]) => recordId).sort();
  for (const recordId of duplicateRecordIds) {
    findings.push({ code: "DUPLICATE_RECORD_ID", recordId });
  }

  // Only uniquely identified records receive vertices. IDs are sorted so
  // equivalent input ordering yields the same source-ID-to-vertex mapping.
  const recordIds = [...counts].filter(([, count]) => count === 1)
    .map(([id]) => id).sort();
  const vertexByRecordId = new Map(recordIds.map((id, index) => [id, index + 1]));
  const vertices = recordIds.map((recordId) => ({
    recordId,
    vertexId: vertexByRecordId.get(recordId),
  }));
  const edges = [];
  const unresolvedParentReferences = [];

  for (const record of records) {
    const id = record?.recordId;
    if (typeof id !== "string" || vertexByRecordId.get(id) === undefined) continue;
    const parent = record.parentRecordId;
    if (parent === null || parent === undefined || parent === "") continue;
    if (typeof parent !== "string" || parent.trim() !== parent) {
      findings.push({ code: "MALFORMED_PARENT_REFERENCE", recordId: id, parentRecordId: parent });
      continue;
    }
    const parentVertex = vertexByRecordId.get(parent);
    if (parentVertex === undefined) {
      unresolvedParentReferences.push({ recordId: id, parentRecordId: parent });
      findings.push({ code: "UNRESOLVED_PARENT_REFERENCE", recordId: id, parentRecordId: parent });
      continue;
    }
    edges.push({ childVertexId: vertexByRecordId.get(id), parentVertexId: parentVertex });
  }
  edges.sort((a, b) => a.childVertexId - b.childVertexId || a.parentVertexId - b.parentVertexId);

  // Bind the digest to both the Lean-facing topology and the source-ID mapping.
  const graphPayload = {
    vertices: vertices.map(({ recordId, vertexId }) => ({ recordId, vertexId })),
    edges: edges.map(({ childVertexId, parentVertexId }) => [childVertexId, parentVertexId]),
  };
  const leanGraphInput = {
    vertices: vertices.map(({ vertexId }) => vertexId),
    edges: edges.map(({ childVertexId, parentVertexId }) => [childVertexId, parentVertexId]),
  };
  const canonicalGraph = JSON.stringify(graphPayload);
  const graphDigest = createHash("sha256").update(canonicalGraph, "utf8").digest("hex");
  const identityIntegrityCertain = findings.every((f) =>
    !["MALFORMED_RECORD_ID", "DUPLICATE_RECORD_ID", "MALFORMED_PARENT_REFERENCE",
      "UNRESOLVED_PARENT_REFERENCE"].includes(f.code));
  const certificateReady = identityIntegrityCertain &&
    findings.every((f) => !f.code.startsWith("MISSING_OR_MALFORMED_SOURCE_")) &&
    typeof sourceCompletenessVerified === "boolean" &&
    sourceCompletenessVerified === true;

  return {
    sourceReadId: typeof sourceReadId === "string" ? sourceReadId : null,
    sourceDigest: typeof sourceDigest === "string" ? sourceDigest : null,
    sourceCompletenessVerified: sourceCompletenessVerified === true,
    vertices,
    edges,
    unresolvedParentReferences,
    findings,
    identityIntegrityCertain,
    graphPayload,
    leanGraphInput,
    graphDigest,
    certificateReady,
  };
}

/**
 * Export a versioned, deterministic handoff artifact for the Lean adapter.
 *
 * The digest binds the source-ID mapping and numeric graph input together.
 * Lean must still check the mapping/graph obligations; this JSON artifact is
 * not itself a Lean proof or an assertion of source completeness.
 */
export function serializeGraphInputCertificate(certificate) {
  if (!certificate || typeof certificate !== "object" ||
      typeof certificate.graphDigest !== "string" ||
      !Array.isArray(certificate.vertices) ||
      !Array.isArray(certificate.edges) ||
      !certificate.leanGraphInput ||
      !Array.isArray(certificate.leanGraphInput.vertices) ||
      !Array.isArray(certificate.leanGraphInput.edges)) {
    throw new TypeError("Malformed graph-input certificate");
  }

  const payload = {
    schema: "yggdrasil-parent-graph-v1",
    sourceReadId: certificate.sourceReadId ?? null,
    sourceDigest: certificate.sourceDigest ?? null,
    sourceCompletenessVerified: certificate.sourceCompletenessVerified === true,
    graphDigest: certificate.graphDigest,
    sourceVertexBindings: certificate.vertices.map(({ recordId, vertexId }) =>
      ({ recordId, vertexId })),
    graphInput: {
      vertices: [...certificate.leanGraphInput.vertices],
      edges: certificate.leanGraphInput.edges.map(([child, parent]) => [child, parent]),
    },
    findings: [...(certificate.findings ?? [])],
    certificateReady: certificate.certificateReady === true,
  };
  return JSON.stringify(payload);
}
/**
 * Verify a serialized handoff before its graph input is trusted.
 * This checks structural consistency and recomputes graphDigest. It does not
 * prove that source records are complete or that sourceDigest is authentic.
 */
export function verifySerializedGraphInputArtifact(serialized) {
  let artifact;
  try {
    artifact = JSON.parse(serialized);
  } catch {
    return { valid: false, code: "INVALID_JSON" };
  }
  if (!artifact || typeof artifact !== "object" ||
      artifact.schema !== "yggdrasil-parent-graph-v1" ||
      !Array.isArray(artifact.sourceVertexBindings) ||
      !artifact.graphInput ||
      !Array.isArray(artifact.graphInput.vertices) ||
      !Array.isArray(artifact.graphInput.edges) ||
      typeof artifact.graphDigest !== "string") {
    return { valid: false, code: "MALFORMED_ARTIFACT" };
  }

  const bindings = artifact.sourceVertexBindings;
  const recordIds = bindings.map((binding) => binding?.recordId);
  const vertexIds = bindings.map((binding) => binding?.vertexId);
  if (bindings.some((binding) =>
      !binding || typeof binding.recordId !== "string" ||
      binding.recordId.length === 0 || binding.recordId.trim() !== binding.recordId ||
      !Number.isSafeInteger(binding.vertexId) || binding.vertexId < 1) ||
      new Set(recordIds).size !== recordIds.length ||
      new Set(vertexIds).size !== vertexIds.length) {
    return { valid: false, code: "INVALID_SOURCE_VERTEX_BINDINGS" };
  }
  const sortedVertices = [...vertexIds].sort((a, b) => a - b);
  const graphVertices = artifact.graphInput.vertices;
  if (graphVertices.some((v) => !Number.isSafeInteger(v) || v < 1) ||
      JSON.stringify([...graphVertices].sort((a, b) => a - b)) !== JSON.stringify(sortedVertices) ||
      artifact.graphInput.edges.some((edge) =>
        !Array.isArray(edge) || edge.length !== 2 ||
        !edge.every((v) => Number.isSafeInteger(v) && v >= 1) ||
        !sortedVertices.includes(edge[0]) || !sortedVertices.includes(edge[1]))) {
    return { valid: false, code: "GRAPH_MAPPING_MISMATCH" };
  }

  const canonicalGraph = JSON.stringify({
    vertices: bindings.map(({ recordId, vertexId }) => ({ recordId, vertexId })),
    edges: artifact.graphInput.edges.map(([child, parent]) => [child, parent]),
  });
  const actualDigest = createHash("sha256").update(canonicalGraph, "utf8").digest("hex");
  if (actualDigest !== artifact.graphDigest) {
    return { valid: false, code: "GRAPH_DIGEST_MISMATCH" };
  }
  return {
    valid: true,
    code: "VERIFIED_GRAPH_PAYLOAD",
    sourceCompletenessVerified: artifact.sourceCompletenessVerified === true,
    certificateReady: artifact.certificateReady === true,
  };
}
/**
/**
 * Construct a source-read evidence manifest from the records actually returned
 * by a source adapter. The digest covers a canonical graph-relevant projection;
 * this records the adapter's claims but does not authenticate the adapter.
 */
export function buildSourceReadEvidenceManifest(records, {
  sourceReadId,
  reportedTotalRecordCount,
  paginationComplete,
  sourceScope,
} = {}) {
  if (!Array.isArray(records)) throw new TypeError("records must be an array");
  const projection = records.map((record) => ({
    recordId: record?.recordId,
    parentRecordId: record?.parentRecordId ?? null,
  })).sort((a, b) => String(a.recordId).localeCompare(String(b.recordId)));
  const sourceDigest = createHash("sha256")
    .update(JSON.stringify(projection), "utf8")
    .digest("hex");
  return {
    schema: "yggdrasil-source-read-evidence-v1",
    sourceReadId: typeof sourceReadId === "string" ? sourceReadId : null,
    sourceDigest,
    returnedRecordCount: records.length,
    reportedTotalRecordCount,
    paginationComplete: paginationComplete === true,
    sourceScope: typeof sourceScope === "string" ? sourceScope : "",
    digestAlgorithm: "sha256",
  };
}

/**
 * Validate the shape and internal consistency of a source-read evidence manifest.
 * This establishes consistency, not authenticity: the manifest must ultimately
 * be produced by a trusted source adapter to be authoritative.
 */
export function validateSourceReadEvidence(evidence, artifact) {
  if (!evidence || typeof evidence !== "object" ||
      evidence.schema !== "yggdrasil-source-read-evidence-v1" ||
      typeof evidence.sourceReadId !== "string" ||
      evidence.sourceReadId !== artifact.sourceReadId ||
      typeof evidence.sourceDigest !== "string" ||
      evidence.sourceDigest !== artifact.sourceDigest ||
      !Number.isSafeInteger(evidence.returnedRecordCount) ||
      evidence.returnedRecordCount < 0 ||
      !Number.isSafeInteger(evidence.reportedTotalRecordCount) ||
      evidence.reportedTotalRecordCount < 0 ||
      evidence.returnedRecordCount !== evidence.reportedTotalRecordCount ||
      evidence.returnedRecordCount !== artifact.sourceVertexBindings.length ||
      evidence.paginationComplete !== true ||
      typeof evidence.sourceScope !== "string" || evidence.sourceScope.trim().length === 0 ||
      evidence.digestAlgorithm !== "sha256") {
    return { valid: false, code: "SOURCE_READ_EVIDENCE_INVALID" };
  }
  return { valid: true, code: "SOURCE_READ_EVIDENCE_CONSISTENT" };
}

/**
 * Trusted-consumer gate: downstream code receives graphInput only when the
 * artifact verifies, has no findings, and a source-read evidence manifest
 * matches its source identity, digest, count, scope, and pagination status.
 *
 * Manifest consistency is not source authenticity. A trusted adapter must
 * produce the evidence; this module cannot prove that caller-supplied claims
 * are true merely because they are internally consistent.
 */
export function consumeVerifiedGraphInputArtifact(serialized, {
  sourceReadEvidence = null,
} = {}) {
  const verification = verifySerializedGraphInputArtifact(serialized);
  if (!verification.valid) {
    return { accepted: false, code: verification.code };
  }
  const artifact = JSON.parse(serialized);
  if (typeof artifact.sourceReadId !== "string" || artifact.sourceReadId.length === 0 ||
      artifact.sourceReadId.trim() !== artifact.sourceReadId ||
      typeof artifact.sourceDigest !== "string" || artifact.sourceDigest.length === 0 ||
      artifact.sourceDigest.trim() !== artifact.sourceDigest) {
    return { accepted: false, code: "INVALID_SOURCE_METADATA" };
  }
  if (!Array.isArray(artifact.findings) || artifact.findings.length !== 0) {
    return { accepted: false, code: "SOURCE_FINDINGS_PRESENT" };
  }
  if (artifact.certificateReady !== true || artifact.sourceCompletenessVerified !== true) {
    return { accepted: false, code: "SOURCE_COMPLETENESS_NOT_INDEPENDENTLY_ATTESTED" };
  }
  const evidenceCheck = validateSourceReadEvidence(sourceReadEvidence, artifact);
  if (!evidenceCheck.valid) {
    return { accepted: false, code: evidenceCheck.code };
  }
  return {
    accepted: true,
    code: "ACCEPTED_VERIFIED_GRAPH_INPUT",
    sourceReadId: artifact.sourceReadId,
    sourceDigest: artifact.sourceDigest,
    graphDigest: artifact.graphDigest,
    graphInput: artifact.graphInput,
    sourceVertexBindings: artifact.sourceVertexBindings.map(({ recordId, vertexId }) => ({ recordId, vertexId })),
  };
}
