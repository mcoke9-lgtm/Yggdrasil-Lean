import test from "node:test";
import assert from "node:assert/strict";
import { analyze, parseCsv, parseInput, createManifest, sha256, summarize } from "../src/engine.js";

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

test("descriptive statistics calculate mean, median, range and sample deviation", () => {
  const stats = summarize([1, 2, 3, 4]);
  assert.equal(stats.count, 4);
  assert.equal(stats.sum, 10);
  assert.equal(stats.mean, 2.5);
  assert.equal(stats.median, 2.5);
  assert.equal(stats.min, 1);
  assert.equal(stats.max, 4);
  assert.ok(Math.abs(stats.sample_standard_deviation - Math.sqrt(5 / 3)) < 1e-12);
});

test("single observation has no sample standard deviation", () => {
  assert.equal(summarize([9]).sample_standard_deviation, null);
});

test("material comparison groups records, ranks inspected mass and preserves source lineage", () => {
  const report = analyze([
    { material: "Wood", quantity_kg: 10, reusable_kg: 8 },
    { material: "Steel", quantity_kg: 20, reusable_kg: 10 },
    { material: "Wood", quantity_kg: 30, reusable_kg: 21 }
  ]);
  const comparison = report.results.material_comparison;
  assert.equal(comparison[0].material, "Wood");
  assert.equal(comparison[0].inspected_kg, 40);
  assert.equal(comparison[0].reusable_kg, 29);
  assert.equal(comparison[0].record_count, 2);
  assert.deepEqual(comparison[0].source_record_numbers, [1, 3]);
  assert.equal(comparison[0].recovery_rate, 29 / 40);
  assert.equal(comparison[1].material, "Steel");
  assert.equal(comparison[1].source_record_numbers[0], 2);
});

test("material labels cannot corrupt aggregate object keys", () => {
  const report = analyze([
    { material: "__proto__", quantity_kg: 4, reusable_kg: 2 },
    { material: "constructor", quantity_kg: 3, reusable_kg: 1 }
  ]);
  assert.equal(report.results.by_material["__proto__"].quantity_kg, 4);
  assert.equal(report.results.by_material.constructor.quantity_kg, 3);
});

test("aggregate overflow is rejected rather than emitting Infinity", () => {
  assert.throws(() => analyze([
    { material: "Huge A", quantity_kg: 1e308, reusable_kg: 0 },
    { material: "Huge B", quantity_kg: 1e308, reusable_kg: 0 }
  ]), /Aggregate overflow/);
});


test("evidence classifications and source references are retained without authentication claims", () => {
  const report = analyze([
    { material: "Wood", quantity_kg: 10, reusable_kg: 8, evidence_type: "directly_measured", source_reference: "scale-log-17" },
    { material: "Steel", quantity_kg: 5, reusable_kg: 4, evidence_type: "reported_by_source", source_reference: "operator-interview-2" },
    { material: "Plastic", quantity_kg: 3, reusable_kg: 1 }
  ]);
  assert.equal(report.evidence.classification_counts.directly_measured, 1);
  assert.equal(report.evidence.classification_counts.reported_by_source, 1);
  assert.equal(report.evidence.classification_counts.unknown, 1);
  assert.equal(report.evidence.references.length, 2);
  assert.match(report.evidence.note, /does not authenticate/);
});

test("complete measurement bounds produce conservative intervals, not confidence intervals", () => {
  const report = analyze([
    { material: "Wood", quantity_kg: 10, reusable_kg: 8, quantity_min_kg: 9, quantity_max_kg: 11, reusable_min_kg: 7, reusable_max_kg: 9 },
    { material: "Steel", quantity_kg: 20, reusable_kg: 10, quantity_min_kg: 18, quantity_max_kg: 22, reusable_min_kg: 8, reusable_max_kg: 12 }
  ]);
  assert.equal(report.uncertainty.status, "complete_record_bounds");
  assert.deepEqual(report.uncertainty.inspected_kg, { min: 27, max: 33 });
  assert.deepEqual(report.uncertainty.reusable_kg, { min: 15, max: 21 });
  assert.ok(Math.abs(report.uncertainty.recovery_fraction.min - 15 / 33) < 1e-12);
  assert.ok(Math.abs(report.uncertainty.recovery_fraction.max - 21 / 27) < 1e-12);
  assert.match(report.uncertainty.interpretation, /not a probability interval or confidence interval/);
});

test("missing bounds prevent a misleading complete aggregate interval", () => {
  const report = analyze([
    { material: "Wood", quantity_kg: 10, reusable_kg: 8, quantity_min_kg: 9, quantity_max_kg: 11, reusable_min_kg: 7, reusable_max_kg: 9 },
    { material: "Steel", quantity_kg: 20, reusable_kg: 10 }
  ]);
  assert.equal(report.uncertainty.status, "incomplete_record_bounds");
  assert.equal(report.uncertainty.inspected_kg, null);
  assert.equal(report.uncertainty.records_with_bounds, 1);
});

test("invalid, incomplete or reversed measurement bounds reject the record", () => {
  const report = analyze([
    { material: "Good", quantity_kg: 10, reusable_kg: 8, quantity_min_kg: 9, quantity_max_kg: 11, reusable_min_kg: 7, reusable_max_kg: 9 },
    { material: "Half", quantity_kg: 10, reusable_kg: 8, quantity_min_kg: 9 },
    { material: "Reversed", quantity_kg: 10, reusable_kg: 8, quantity_min_kg: 12, quantity_max_kg: 11 }
  ]);
  assert.equal(report.quality.accepted_records, 1);
  assert.equal(report.quality.rejected_records, 2);
  assert.equal(report.quality.findings.length, 2);
});

test("unsupported evidence types are rejected rather than silently relabeled", () => {
  const report = analyze([
    { material: "Unknown", quantity_kg: 10, reusable_kg: 5, evidence_type: "verified_by_ai" }
  ]);
  assert.equal(report.quality.accepted_records, 0);
  assert.equal(report.quality.rejected_records, 1);
  assert.match(report.quality.findings[0], /evidence_type must be one of/);
});
