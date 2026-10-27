import {
  buildGraphInputCertificate,
  buildSourceReadEvidenceManifest,
  serializeGraphInputCertificate,
} from "./graph-input-certificate.mjs";

/**
 * Adapt one complete, already-retrieved source response into the gated graph
 * handoff. This validates the response contract before asserting completeness.
 *
 * The adapter must be invoked at the connector boundary with the actual
 * response object, not with caller-reconstructed summary fields. This generic
 * reference adapter cannot itself authenticate a connector or prove that the
 * connector's reported total is truthful.
 *
 * Expected read shape:
 * {
 *   sourceReadId, sourceScope, records, totalRecordCount, nextCursor
 * }
 */
export function adaptCompleteSourceRead(read) {
  if (!read || typeof read !== "object" || Array.isArray(read) ||
      typeof read.sourceReadId !== "string" || !read.sourceReadId.trim() ||
      read.sourceReadId.trim() !== read.sourceReadId ||
      typeof read.sourceScope !== "string" || !read.sourceScope.trim() ||
      !Array.isArray(read.records) ||
      !Number.isSafeInteger(read.totalRecordCount) || read.totalRecordCount < 0) {
    return { accepted: false, code: "SOURCE_READ_RESPONSE_MALFORMED" };
  }
  if (read.nextCursor !== null && read.nextCursor !== undefined) {
    return { accepted: false, code: "SOURCE_READ_PAGINATION_INCOMPLETE" };
  }
  if (read.totalRecordCount !== read.records.length) {
    return { accepted: false, code: "SOURCE_READ_COUNT_MISMATCH" };
  }

  const graphRecords = read.records.map((record) => ({
    recordId: record?.recordId,
    parentRecordId: record?.parentRecordId ?? null,
  }));
  const evidence = buildSourceReadEvidenceManifest(graphRecords, {
    sourceReadId: read.sourceReadId,
    reportedTotalRecordCount: read.totalRecordCount,
    paginationComplete: true,
    sourceScope: read.sourceScope,
  });
  const certificate = buildGraphInputCertificate(graphRecords, {
    sourceReadId: read.sourceReadId,
    sourceDigest: evidence.sourceDigest,
    sourceCompletenessVerified: true,
  });
  if (!certificate.certificateReady || certificate.findings.length !== 0) {
    return {
      accepted: false,
      code: "SOURCE_GRAPH_CERTIFICATE_NOT_READY",
      findings: certificate.findings,
    };
  }
  return {
    accepted: true,
    code: "SOURCE_READ_ADAPTED",
    serializedGraphInput: serializeGraphInputCertificate(certificate),
    sourceReadEvidence: evidence,
  };
}
