import { createHash } from "node:crypto";

const sha256 = (value) => createHash("sha256").update(value, "utf8").digest("hex");

function canonicalize(value) {
  if (value === undefined) return { $type: "undefined" };
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(canonicalize);
  return Object.fromEntries(
    Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]),
  );
}

const canonicalJson = (value) => JSON.stringify(canonicalize(value));

/**
 * Build a deterministic full-field snapshot from connector-shaped Airtable rows.
 * This function does not fetch data, persist files, or attest that pagination is complete.
 * Sparse keys remain distinct from explicit null, empty string, and empty arrays.
 */
export function buildFullFieldSnapshot({
  records,
  schemaFieldIds,
  baseId,
  tableId,
  capturedAt,
  nextCursor = null,
  reportedTotalRecordCount,
}) {
  if (!Array.isArray(records) || !Array.isArray(schemaFieldIds) ||
      !schemaFieldIds.every((id) => typeof id === "string" && id.length > 0) ||
      new Set(schemaFieldIds).size !== schemaFieldIds.length ||
      typeof baseId !== "string" || !baseId.trim() ||
      typeof tableId !== "string" || !tableId.trim() ||
      typeof capturedAt !== "string" || !capturedAt.trim() ||
      !Number.isSafeInteger(reportedTotalRecordCount) || reportedTotalRecordCount < 0) {
    return { accepted: false, code: "SNAPSHOT_INPUT_MALFORMED" };
  }
  if (nextCursor !== null && nextCursor !== undefined) {
    return { accepted: false, code: "SNAPSHOT_PAGINATION_INCOMPLETE" };
  }
  if (records.length !== reportedTotalRecordCount) {
    return { accepted: false, code: "SNAPSHOT_COUNT_MISMATCH" };
  }
  const ids = records.map((r) => r?.id);
  if (ids.some((id) => typeof id !== "string" || !/^rec[A-Za-z0-9]{14}$/.test(id))) {
    return { accepted: false, code: "SNAPSHOT_STORAGE_ID_MISSING_OR_MALFORMED" };
  }
  if (new Set(ids).size !== ids.length) {
    return { accepted: false, code: "SNAPSHOT_DUPLICATE_STORAGE_ID" };
  }
  const rows = records.map((record) => {
    if (!record.cellValuesByFieldId || typeof record.cellValuesByFieldId !== "object" ||
        Array.isArray(record.cellValuesByFieldId) ||
        typeof record.createdTime !== "string" || !record.createdTime) {
      return null;
    }
    const fieldIds = Object.keys(record.cellValuesByFieldId).sort();
    const fields = Object.fromEntries(fieldIds.map((fieldId) => {
      const value = record.cellValuesByFieldId[fieldId];
      return [fieldId, {
        present: true,
        value: canonicalize(value),
        hash: sha256(canonicalJson({ present: true, value })),
      }];
    }));
    const schemaCoverage = Object.fromEntries([...schemaFieldIds].sort().map((fieldId) => [
      fieldId, Object.prototype.hasOwnProperty.call(record.cellValuesByFieldId, fieldId),
    ]));
    const rowPayload = { storageId: record.id, createdTime: record.createdTime, fields, schemaCoverage };
    return { ...rowPayload, rowHash: sha256(canonicalJson(rowPayload)) };
  });
  if (rows.some((row) => row === null)) {
    return { accepted: false, code: "SNAPSHOT_RECORD_SHAPE_MALFORMED" };
  }
  rows.sort((a, b) => a.storageId.localeCompare(b.storageId));
  const manifest = {
    schema: "adaptive-filesystem-full-field-snapshot-v1",
    baseId, tableId, capturedAt, reportedTotalRecordCount,
    returnedRecordCount: records.length,
    paginationComplete: true,
    schemaFieldIds: [...schemaFieldIds].sort(),
    rows,
  };
  return {
    accepted: true,
    code: "SNAPSHOT_BUILT",
    snapshot: manifest,
    snapshotHash: sha256(canonicalJson(manifest)),
  };
}

/**
 * Check internal snapshot consistency before comparison. This detects accidental
 * or partial tampering, but cannot authenticate a snapshot if an attacker can
 * rewrite every value and recompute every hash; that requires a separately
 * trusted snapshot hash or signed capture receipt.
 */
function validateSnapshot(snapshot) {
  if (!snapshot || snapshot.schema !== "adaptive-filesystem-full-field-snapshot-v1" ||
      typeof snapshot.baseId !== "string" || !snapshot.baseId.trim() ||
      typeof snapshot.tableId !== "string" || !snapshot.tableId.trim() ||
      typeof snapshot.capturedAt !== "string" || !snapshot.capturedAt.trim() ||
      !Number.isSafeInteger(snapshot.reportedTotalRecordCount) ||
      snapshot.reportedTotalRecordCount < 0 ||
      !Number.isSafeInteger(snapshot.returnedRecordCount) ||
      !Array.isArray(snapshot.rows) ||
      snapshot.returnedRecordCount !== snapshot.rows.length ||
      snapshot.reportedTotalRecordCount !== snapshot.returnedRecordCount ||
      snapshot.paginationComplete !== true ||
      !Array.isArray(snapshot.schemaFieldIds) ||
      !snapshot.schemaFieldIds.every((id) => typeof id === "string" && id.length > 0) ||
      new Set(snapshot.schemaFieldIds).size !== snapshot.schemaFieldIds.length) {
    return false;
  }
  for (const row of snapshot.rows) {
    if (!row || typeof row.storageId !== "string" ||
        !/^rec[A-Za-z0-9]{14}$/.test(row.storageId) ||
        typeof row.createdTime !== "string" || !row.createdTime ||
        !row.fields || typeof row.fields !== "object" || Array.isArray(row.fields) ||
        !row.schemaCoverage || typeof row.schemaCoverage !== "object" ||
        Array.isArray(row.schemaCoverage) || typeof row.rowHash !== "string") {
      return false;
    }
    const expectedCoverageIds = [...snapshot.schemaFieldIds].sort();
    if (JSON.stringify(Object.keys(row.schemaCoverage).sort()) !== JSON.stringify(expectedCoverageIds) ||
        Object.values(row.schemaCoverage).some((present) => typeof present !== "boolean")) {
      return false;
    }
    for (const [fieldId, field] of Object.entries(row.fields)) {
      if (!field || field.present !== true || typeof field.hash !== "string" ||
          field.hash !== sha256(canonicalJson({ present: true, value: field.value })) ||
          row.schemaCoverage[fieldId] !== true) {
        return false;
      }
    }
    for (const [fieldId, present] of Object.entries(row.schemaCoverage)) {
      if (present !== Object.prototype.hasOwnProperty.call(row.fields, fieldId)) return false;
    }
    const { rowHash, ...rowPayload } = row;
    if (rowHash !== sha256(canonicalJson(rowPayload))) return false;
  }
  const ids = snapshot.rows.map((row) => row.storageId);
  return new Set(ids).size === ids.length;
}

/** Compare two internally consistent snapshots by immutable Airtable storage ID. */
export function compareFullFieldSnapshots(previous, current) {
  if (!validateSnapshot(previous) || !validateSnapshot(current)) {
    return { accepted: false, code: "SNAPSHOT_INTEGRITY_INVALID" };
  }
  const duplicates = (rows) => {
    const seen = new Set(), repeated = new Set();
    for (const row of rows) {
      if (seen.has(row.storageId)) repeated.add(row.storageId);
      seen.add(row.storageId);
    }
    return [...repeated].sort();
  };
  const duplicatePrevious = duplicates(previous.rows);
  const duplicateCurrent = duplicates(current.rows);
  if (duplicatePrevious.length || duplicateCurrent.length) {
    return { accepted: false, code: "SNAPSHOT_DUPLICATE_STORAGE_ID", duplicatePrevious, duplicateCurrent };
  }
  const before = new Map(previous.rows.map((row) => [row.storageId, row]));
  const after = new Map(current.rows.map((row) => [row.storageId, row]));
  const added = [], missing = [], changed = [];
  for (const [id, row] of after) {
    if (!before.has(id)) { added.push(id); continue; }
    const old = before.get(id);
    if (old.rowHash === row.rowHash) continue;
    const fieldIds = [...new Set([...Object.keys(old.fields ?? {}), ...Object.keys(row.fields ?? {})])].sort();
    const changedFields = fieldIds.filter((fieldId) => {
      const a = old.fields?.[fieldId], b = row.fields?.[fieldId];
      return !a || !b || a.present !== b.present || a.hash !== b.hash;
    });
    changed.push({
      storageId: id,
      createdTimeChanged: old.createdTime !== row.createdTime,
      changedFields,
      previousRowHash: old.rowHash,
      currentRowHash: row.rowHash,
    });
  }
  for (const id of before.keys()) if (!after.has(id)) missing.push(id);
  added.sort(); missing.sort(); changed.sort((a, b) => a.storageId.localeCompare(b.storageId));
  return {
    accepted: true, code: "SNAPSHOTS_COMPARED",
    previousCount: previous.rows.length, currentCount: current.rows.length,
    added, missing, changed,
  };
}
