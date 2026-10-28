import test from "node:test";
import assert from "node:assert/strict";
import { buildFullFieldSnapshot, compareFullFieldSnapshots } from "./full-field-snapshot-integrity.mjs";

const schemaFieldIds = ["fldA", "fldB", "fldC"];
const record = (id, fields, createdTime = "2026-10-27T00:00:00.000Z") =>
  ({ id, createdTime, cellValuesByFieldId: fields });
const build = (records, total = records.length, nextCursor = null) =>
  buildFullFieldSnapshot({
    records, schemaFieldIds, baseId: "app00000000000000", tableId: "tbl00000000000000",
    capturedAt: "2026-10-27T12:00:00.000Z", nextCursor,
    reportedTotalRecordCount: total,
  });

test("stable hashes ignore row order and object-key order", () => {
  const a = build([record("rec00000000000001", { fldA: "x", fldB: { z: 2, a: 1 } })]);
  const b = build([record("rec00000000000001", { fldB: { a: 1, z: 2 }, fldA: "x" })]);
  assert.equal(a.accepted, true);
  assert.equal(a.snapshotHash, b.snapshotHash);
});

test("sparse field differs from explicit empty and null", () => {
  const sparse = build([record("rec00000000000001", { fldA: "x" })]);
  const empty = build([record("rec00000000000001", { fldA: "x", fldB: "" })]);
  const nulled = build([record("rec00000000000001", { fldA: "x", fldB: null })]);
  assert.notEqual(sparse.snapshotHash, empty.snapshotHash);
  assert.notEqual(empty.snapshotHash, nulled.snapshotHash);
  assert.equal(sparse.snapshot.rows[0].schemaCoverage.fldB, false);
  assert.equal(empty.snapshot.rows[0].schemaCoverage.fldB, true);
});

test("rejects incomplete pagination and count mismatch", () => {
  assert.equal(build([record("rec00000000000001", {})], 1, "cursor-2").code,
    "SNAPSHOT_PAGINATION_INCOMPLETE");
  assert.equal(build([record("rec00000000000001", {})], 2).code, "SNAPSHOT_COUNT_MISMATCH");
});

test("rejects duplicate or malformed storage IDs", () => {
  assert.equal(build([record("rec00000000000001", {}), record("rec00000000000001", {})]).code,
    "SNAPSHOT_DUPLICATE_STORAGE_ID");
  assert.equal(build([record("bad-id", {})]).code, "SNAPSHOT_STORAGE_ID_MISSING_OR_MALFORMED");
});

test("comparison identifies additions, missing rows, and field changes by storage ID", () => {
  const prev = build([
    record("rec00000000000001", { fldA: "old" }),
    record("rec00000000000002", { fldA: "keep" }),
  ]).snapshot;
  const curr = build([
    record("rec00000000000001", { fldA: "new" }),
    record("rec00000000000003", { fldA: "added" }),
  ]).snapshot;
  const diff = compareFullFieldSnapshots(prev, curr);
  assert.equal(diff.accepted, true);
  assert.deepEqual(diff.added, ["rec00000000000003"]);
  assert.deepEqual(diff.missing, ["rec00000000000002"]);
  assert.deepEqual(diff.changed[0].changedFields, ["fldA"]);
});

test("comparison rejects duplicate IDs even in hand-constructed snapshots", () => {
  const snap = build([record("rec00000000000001", {})]).snapshot;
  const duplicated = { ...snap, rows: [...snap.rows, snap.rows[0]] };
  assert.equal(compareFullFieldSnapshots(duplicated, snap).code, "SNAPSHOT_DUPLICATE_STORAGE_ID");
});


test("comparison rejects field tampering when the stored field and row hashes are stale", () => {
  const previous = build([record("rec00000000000001", { fldA: "original" })]).snapshot;
  const current = build([record("rec00000000000001", { fldA: "changed" })]).snapshot;
  current.rows[0].fields.fldA.value = "forged-with-stale-hashes";
  assert.deepEqual(compareFullFieldSnapshots(previous, current), {
    accepted: false,
    code: "SNAPSHOT_INTEGRITY_INVALID",
  });
});

test("comparison rejects internally inconsistent snapshot counts", () => {
  const previous = build([record("rec00000000000001", { fldA: "same" })]).snapshot;
  const current = build([record("rec00000000000001", { fldA: "same" })]).snapshot;
  current.returnedRecordCount = 2;
  assert.deepEqual(compareFullFieldSnapshots(previous, current), {
    accepted: false,
    code: "SNAPSHOT_INTEGRITY_INVALID",
  });
});


test("comparison rejects snapshots from different Airtable scopes", () => {
  const previous = build([record("rec00000000000001", { fldA: "same" })]).snapshot;
  const current = build([record("rec00000000000001", { fldA: "same" })]).snapshot;
  current.baseId = "app11111111111111";
  assert.deepEqual(compareFullFieldSnapshots(previous, current), {
    accepted: false,
    code: "SNAPSHOT_SCOPE_MISMATCH",
  });
});

test("snapshot rows use locale-independent storage-ID ordering", () => {
  const upper = "rec0000000000000A";
  const lower = "rec0000000000000a";
  const snapshot = build([
    record(lower, { fldA: "lower" }),
    record(upper, { fldA: "upper" }),
  ]).snapshot;
  assert.deepEqual(snapshot.rows.map((row) => row.storageId), [upper, lower]);
});
