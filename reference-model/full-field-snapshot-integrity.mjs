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

/** Compare two accepted snapshots by immutable Airtable storage ID. */
export function compareFullFieldSnapshots(previous, current) {
  if (!previous || !current || previous.schema !== "adaptive-filesystem-full-field-snapshot-v1" ||
      current.schema !== "adaptive-filesystem-full-field-snapshot-v1" ||
      !Array.isArray(previous.rows) || !Array.isArray(current.rows)) {
    return { accepted: false, code: "SNAPSHOT_COMPARISON_INPUT_MALFORMED" };
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
