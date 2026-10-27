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

  const graphPayload = {
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
    graphDigest,
    certificateReady,
  };
}
