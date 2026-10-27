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
 * Normalize the shape returned by the Airtable table connector and route the
 * same response through the source-read gate. Field IDs must come from the
 * table schema, not be guessed from display values.
 */
export function analyzeAirtableTableResponse(response, {
  sourceReadId,
  baseId,
  tableId,
  recordIdFieldId,
  parentRecordIdFieldId,
} = {}) {
  if (!response || typeof response !== "object" || !Array.isArray(response.records) ||
      !response.metadata || !Number.isSafeInteger(response.metadata.totalRecordCount) ||
      typeof baseId !== "string" || !baseId.trim() ||
      typeof tableId !== "string" || !tableId.trim() ||
      typeof recordIdFieldId !== "string" || !recordIdFieldId.trim() ||
      typeof parentRecordIdFieldId !== "string" || !parentRecordIdFieldId.trim()) {
    return { accepted: false, code: "AIRTABLE_RESPONSE_MALFORMED" };
  }
  const records = response.records.map((record) => ({
    recordId: record?.cellValuesByFieldId?.[recordIdFieldId],
    parentRecordId: record?.cellValuesByFieldId?.[parentRecordIdFieldId] ?? null,
  }));
  if (records.some((record) => typeof record.recordId !== "string" || !record.recordId.trim())) {
    return { accepted: false, code: "AIRTABLE_RECORD_ID_MISSING" };
  }
  return analyzeCompleteSourceRead({
    sourceReadId,
    sourceScope: `airtable:${baseId}/${tableId}; fields:${recordIdFieldId},${parentRecordIdFieldId}`,
    records,
    totalRecordCount: response.metadata.totalRecordCount,
    nextCursor: response.nextCursor ?? null,
  });
}

/**
 * Fetch every page through a trusted connector function, then pass the
 * aggregated connector-returned records to the Airtable response adapter.
 *
 * listRecordsForTable must be a trusted integration wrapper around the actual
 * connector. It receives { baseId, tableId, fieldIds, pageSize, cursor? } and
 * returns the connector's unmodified page response. This function detects
 * inconsistent totals and repeated cursors; it cannot authenticate a malicious
 * or incorrectly implemented connector wrapper.
 */
export async function readAndAnalyzeAirtableTable({
  listRecordsForTable,
  sourceReadId,
  baseId,
  tableId,
  recordIdFieldId,
  parentRecordIdFieldId,
  pageSize = 100,
} = {}) {
  if (typeof listRecordsForTable !== "function" ||
      !Number.isSafeInteger(pageSize) || pageSize < 1 ||
      typeof sourceReadId !== "string" || !sourceReadId.trim() ||
      typeof baseId !== "string" || !baseId.trim() ||
      typeof tableId !== "string" || !tableId.trim() ||
      typeof recordIdFieldId !== "string" || !recordIdFieldId.trim() ||
      typeof parentRecordIdFieldId !== "string" || !parentRecordIdFieldId.trim()) {
    return { accepted: false, code: "AIRTABLE_READ_CONFIG_MALFORMED" };
  }

  const records = [];
  const seenCursors = new Set();
  let cursor;
  let reportedTotalRecordCount = null;
  do {
    const page = await listRecordsForTable({
      baseId,
      tableId,
      fieldIds: [recordIdFieldId, parentRecordIdFieldId],
      pageSize,
      ...(cursor ? { cursor } : {}),
    });
    if (!page || typeof page !== "object" || !Array.isArray(page.records) ||
        !page.metadata || !Number.isSafeInteger(page.metadata.totalRecordCount) ||
        page.metadata.totalRecordCount < 0) {
      return { accepted: false, code: "AIRTABLE_RESPONSE_MALFORMED" };
    }
    if (reportedTotalRecordCount === null) {
      reportedTotalRecordCount = page.metadata.totalRecordCount;
    } else if (reportedTotalRecordCount !== page.metadata.totalRecordCount) {
      return { accepted: false, code: "AIRTABLE_TOTAL_COUNT_CHANGED_DURING_READ" };
    }
    records.push(...page.records);
    const nextCursor = page.nextCursor ?? null;
    if (nextCursor === null) {
      cursor = undefined;
    } else {
      if (typeof nextCursor !== "string" || !nextCursor ||
          seenCursors.has(nextCursor)) {
        return { accepted: false, code: "AIRTABLE_CURSOR_INVALID_OR_REPEATED" };
      }
      seenCursors.add(nextCursor);
      cursor = nextCursor;
    }
    if (records.length > reportedTotalRecordCount) {
      return { accepted: false, code: "AIRTABLE_RETURNED_COUNT_EXCEEDS_TOTAL" };
    }
  } while (cursor);

  return analyzeAirtableTableResponse({
    records,
    metadata: { totalRecordCount: reportedTotalRecordCount },
    nextCursor: null,
  }, {
    sourceReadId,
    baseId,
    tableId,
    recordIdFieldId,
    parentRecordIdFieldId,
  });
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
