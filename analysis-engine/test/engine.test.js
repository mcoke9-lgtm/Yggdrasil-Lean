import test from "node:test";
import assert from "node:assert/strict";
import { analyze, parseCsv, parseInput, createManifest, sha256 } from "../src/engine.js";

const sample = [
  { material: "Wood", quantity_kg: 100, reusable_kg: 70 },
  { material: "Steel", quantity_kg: 80, reusable_kg: 72 },
  { material: "Plastic", quantity_kg: 50, reusable_kg: 20 }
];

test("acceptance dataset produces 230 kg, 162 kg and 70.4348%", () => {
  const report = analyze(sample, { sourceName: "sample.json", sourceText: JSON.stringify(sample) });
  assert.equal(report.results.total_inspected_kg, 230);
  assert.equal(report.results.total_reusable_kg, 162);
  assert.ok(Math.abs(report.results.reusable_percent - 70.43478260869566) < 1e-12);
  assert.equal(report.verification.passed, true);
  assert.equal(report.quality.accepted_records, 3);
});

test("CSV parser supports quoted commas, escaped quotes and CRLF", () => {
  const rows = parseCsv('material,quantity_kg,reusable_kg\r\n"Wood, reclaimed",10,7\r\n"Steel ""A""",5,4\r\n');
  assert.equal(rows[0].material, "Wood, reclaimed");
  assert.equal(rows[1].material, 'Steel "A"');
});

test("invalid values are rejected with a finding", () => {
  const report = analyze([
    { material: "Good", quantity_kg: 10, reusable_kg: 5 },
    { material: "Bad", quantity_kg: 2, reusable_kg: 3 },
    { material: "Missing", quantity_kg: 1 }
  ]);
  assert.equal(report.quality.accepted_records, 1);
  assert.equal(report.quality.rejected_records, 2);
  assert.equal(report.quality.findings.length, 2);
});

test("JSON parser accepts records wrapper", () => {
  assert.deepEqual(parseInput('{"records":[{"material":"x"}]}', "json"), [{ material: "x" }]);
});

test("zero total yields null fraction, not NaN or Infinity", () => {
  const report = analyze([{ material: "Empty", quantity_kg: 0, reusable_kg: 0 }]);
  assert.equal(report.results.reusable_fraction, null);
  assert.equal(report.results.reusable_percent, null);
});

test("manifest hashes exact source and report bytes", () => {
  const source = "sample";
  const report = '{"ok":true}\n';
  const manifest = createManifest(report, source, "report.json", "source.csv");
  assert.equal(manifest.source_sha256, sha256(source));
  assert.equal(manifest.report_sha256, sha256(report));
});

test("duplicate CSV headers and malformed quoted fields are rejected", () => {
  assert.throws(() => parseCsv("x,x\n1,2\n"), /duplicate headers/);
  assert.throws(() => parseCsv('x\n"unfinished'), /unterminated/);
});
