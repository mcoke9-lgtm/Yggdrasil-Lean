import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const [leftPath, rightPath] = process.argv.slice(2);
if (!leftPath || !rightPath) {
  throw new Error("Usage: node src/compare-portability.js <node-20-results.json> <node-22-results.json>");
}
const [left, right] = await Promise.all([
  readFile(leftPath, "utf8").then(JSON.parse),
  readFile(rightPath, "utf8").then(JSON.parse)
]);

assert.notEqual(left.node_version, right.node_version, "comparison must use distinct Node.js versions");
assert.deepEqual(Object.keys(left.results).sort(), Object.keys(right.results).sort());

function portableReceipt(receipt) {
  const {
    execution_id, started_at, completed_at, environment,
    ...environmentIndependentFields
  } = receipt;
  return environmentIndependentFields;
}

for (const format of Object.keys(left.results)) {
  const a = left.results[format];
  const b = right.results[format];
  assert.equal(a.source_bytes_sha256, b.source_bytes_sha256, `${format}: exact source bytes must match`);
  assert.equal(a.outcome, "completed");
  assert.equal(b.outcome, "completed");
  assert.deepEqual(a.report, b.report, `${format}: report semantics and serialized values must match exactly`);
  assert.deepEqual(a.verification, b.verification, `${format}: verification results must match exactly`);
  assert.deepEqual(
    portableReceipt(a.receipt),
    portableReceipt(b.receipt),
    `${format}: environment-independent receipt fields must match`
  );
  assert.notEqual(a.receipt.execution_id, b.receipt.execution_id, `${format}: execution IDs should be unique`);
  assert.notEqual(a.receipt.environment.runtime_version, b.receipt.environment.runtime_version);
}

process.stdout.write("Cross-runtime portability comparison passed for CSV and JSON.\\n");
