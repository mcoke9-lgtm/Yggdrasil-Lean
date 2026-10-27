/**
 * Pure reference resolver for Adaptive Filesystem review-history records.
 *
 * This module is storage-neutral. It does not read or write Airtable, enforce
 * backend integrity, or establish that an input dataset is exhaustive.
 */

export const Outcome = Object.freeze({
  RESOLVED: "RESOLVED",
  NOT_APPLICABLE: "NOT_APPLICABLE",
  MISSING_PARENT: "MISSING_PARENT",
  AMBIGUOUS_PARENT: "AMBIGUOUS_PARENT",
  MISSING_SOURCE_ANCHOR: "MISSING_SOURCE_ANCHOR",
  AMBIGUOUS_SOURCE_ANCHOR: "AMBIGUOUS_SOURCE_ANCHOR",
  SOURCE_BRANCH_MISMATCH: "SOURCE_BRANCH_MISMATCH",
  MISSING_REVISION_TARGET: "MISSING_REVISION_TARGET",
  AMBIGUOUS_REVISION_TARGET: "AMBIGUOUS_REVISION_TARGET",
  MALFORMED_REFERENCE: "MALFORMED_REFERENCE",
  PARENT_CYCLE: "PARENT_CYCLE",
  DUPLICATE_RECORD_ID: "DUPLICATE_RECORD_ID",
  DUPLICATE_BRANCH_SEQUENCE: "DUPLICATE_BRANCH_SEQUENCE",
});

const optionalRef = (value) => value === null || value === undefined || value === "";

function resolveExact(records, rawId, missingOutcome, ambiguousOutcome) {
  if (optionalRef(rawId)) return { outcome: Outcome.NOT_APPLICABLE };
  if (typeof rawId !== "string" || rawId.trim() !== rawId) {
    return { outcome: Outcome.MALFORMED_REFERENCE };
  }
  const matches = records.filter((record) => record.recordId === rawId);
  if (matches.length === 0) return { outcome: missingOutcome };
  if (matches.length > 1) return { outcome: ambiguousOutcome, matches: matches.length };
  return { outcome: Outcome.RESOLVED, record: matches[0] };
}

export function hasParentCycle(records) {
  const ids = new Map();
  for (const record of records) {
    if (typeof record.recordId === "string" && !ids.has(record.recordId)) {
      ids.set(record.recordId, record.parentRecordId || null);
    }
  }
  const completed = new Set();
  for (const start of ids.keys()) {
    const path = new Set();
    let current = start;
    while (current && ids.has(current)) {
      if (path.has(current)) return true;
      if (completed.has(current)) break;
      path.add(current);
      current = ids.get(current);
    }
    for (const id of path) completed.add(id);
  }
  return false;
}

export function resolveRecordReferences(records, record) {
  const parent = resolveExact(records, record.parentRecordId,
    Outcome.MISSING_PARENT, Outcome.AMBIGUOUS_PARENT);
  const sourceAnchor = resolveExact(records, record.sourceAnchorId,
    Outcome.MISSING_SOURCE_ANCHOR, Outcome.AMBIGUOUS_SOURCE_ANCHOR);
  const revision = resolveExact(records, record.revisionOfId,
    Outcome.MISSING_REVISION_TARGET, Outcome.AMBIGUOUS_REVISION_TARGET);

  const anchorOutcome = sourceAnchor.outcome === Outcome.RESOLVED &&
      sourceAnchor.record.branchId !== record.sourceBranchId
    ? Outcome.SOURCE_BRANCH_MISMATCH
    : sourceAnchor.outcome;

  const malformed = [record.parentRecordId, record.sourceAnchorId, record.revisionOfId]
    .some((value) => !optionalRef(value) &&
      (typeof value !== "string" || value.trim() !== value));

  return {
    recordId: record.recordId,
    parent: parent.outcome,
    sourceAnchor: anchorOutcome,
    revision: revision.outcome,
    malformedReference: malformed ? Outcome.MALFORMED_REFERENCE : "OK",
    parentCycle: hasParentCycle(records) ? Outcome.PARENT_CYCLE : "OK",
  };
}

export function auditDataset(records) {
  const idCounts = new Map();
  const sequenceCounts = new Map();
  const invalidRecordIds = [];
  const invalidBranchSequences = [];
  for (const record of records) {
    const id = record.recordId;
    if (typeof id !== "string" || id.length === 0 || id.trim() !== id) {
      invalidRecordIds.push({ recordId: id ?? null, reason: "MALFORMED_RECORD_ID" });
    } else {
      idCounts.set(id, (idCounts.get(id) || 0) + 1);
    }

    const branch = record.branchId;
    const sequence = record.sequence;
    if (typeof branch !== "string" || branch.length === 0 || branch.trim() !== branch ||
        !Number.isSafeInteger(sequence) || sequence < 1) {
      invalidBranchSequences.push({ recordId: id ?? null, branchId: branch ?? null,
        sequence: sequence ?? null, reason: "MALFORMED_BRANCH_SEQUENCE" });
    } else {
      const key = JSON.stringify([branch, sequence]);
      sequenceCounts.set(key, (sequenceCounts.get(key) || 0) + 1);
    }
  }
  return {
    invalidRecordIds,
    duplicateRecordIds: [...idCounts].filter(([, count]) => count > 1).map(([id]) => id),
    invalidBranchSequences,
    duplicateBranchSequences: [...sequenceCounts]
      .filter(([, count]) => count > 1).map(([key]) => JSON.parse(key)),
    parentCycle: hasParentCycle(records),
    records: records.map((record) => resolveRecordReferences(records, record)),
  };
}
