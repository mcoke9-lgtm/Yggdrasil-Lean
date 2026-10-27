import {
  buildGraphInputCertificate,
  buildSourceReadEvidenceManifest,
  serializeGraphInputCertificate,
} from "./graph-input-certificate.mjs";
import { analyzeVerifiedGraphInput } from "./verified-graph-consumer.mjs";

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

/**
 * End-to-end reference entry point for the connector boundary.
 * Keep the actual connector response and analysis in this call path so callers
 * do not manually assemble an evidence manifest or graph artifact.
 *
 * This does not make an untrusted caller's response authentic. Production code
 * must invoke it directly with the connector's actual returned response.
 */
export function analyzeCompleteSourceRead(read) {
  const adapted = adaptCompleteSourceRead(read);
  if (!adapted.accepted) return adapted;
  const analysis = analyzeVerifiedGraphInput(adapted.serializedGraphInput, {
    sourceReadEvidence: adapted.sourceReadEvidence,
  });
  if (!analysis.accepted) return analysis;
  return {
    ...analysis,
    code: "SOURCE_READ_ANALYSIS_COMPLETED",
    sourceScope: adapted.sourceReadEvidence.sourceScope,
    sourceRecordCount: adapted.sourceReadEvidence.returnedRecordCount,
  };
}
