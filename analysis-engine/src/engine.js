import { createHash } from "node:crypto";

export const REQUIRED_FIELDS = ["material", "quantity_kg", "reusable_kg"];

export function sha256(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

// Small RFC-4180-style parser: quoted commas, escaped quotes and CRLF are supported.
export function parseCsv(text) {
  const rows = [];
  let row = [], field = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (ch === '"') quoted = false;
      else field += ch;
    } else if (ch === '"' && field.length === 0) quoted = true;
    else if (ch === ",") { row.push(field); field = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.some(value => value.length > 0)) rows.push(row);
      row = [];
    } else field += ch;
  }
  if (quoted) throw new Error("CSV contains an unterminated quoted field");
  row.push(field);
  if (row.some(value => value.length > 0)) rows.push(row);
  if (rows.length < 2) throw new Error("CSV requires a header and at least one data row");
  const headers = rows[0].map(value => value.trim().replace(/^\uFEFF/, ""));
  if (new Set(headers).size !== headers.length) throw new Error("CSV contains duplicate headers");
  return rows.slice(1).map((values, index) => {
    if (values.length !== headers.length) {
      throw new Error(`CSV row ${index + 2} has ${values.length} values; expected ${headers.length}`);
    }
    return Object.fromEntries(headers.map((header, i) => [header, values[i]]));
  });
}

export function parseInput(text, format) {
  if (format === "csv") return parseCsv(text);
  if (format === "json") {
    const parsed = JSON.parse(text);
    const records = Array.isArray(parsed) ? parsed : parsed?.records;
    if (!Array.isArray(records)) throw new Error("JSON must be an array or an object containing a records array");
    return records;
  }
  throw new Error("Supported input formats are CSV and JSON");
}

// Descriptive statistics for observed values. Sample standard deviation is null
// for a single observation; it is not a confidence interval or population estimate.
export function summarize(values) {
  if (!Array.isArray(values) || values.length === 0) {
    return { count: 0, sum: 0, mean: null, median: null, min: null, max: null, sample_standard_deviation: null };
  }
  const sorted = [...values].sort((a, b) => a - b);
  const sum = values.reduce((total, value) => total + value, 0);
  const mean = sum / values.length;
  const median = values.length % 2
    ? sorted[(values.length - 1) / 2]
    : (sorted[values.length / 2 - 1] + sorted[values.length / 2]) / 2;
  // Welford's stable one-pass variance calculation.
  let n = 0, runningMean = 0, squaredDifference = 0;
  for (const value of values) {
    n++;
    const delta = value - runningMean;
    runningMean += delta / n;
    squaredDifference += delta * (value - runningMean);
  }
  const standardDeviation = n > 1 ? Math.sqrt(squaredDifference / (n - 1)) : null;
  return {
    count: values.length, sum, mean, median,
    min: sorted[0], max: sorted[sorted.length - 1],
    sample_standard_deviation: standardDeviation
  };
}

export function analyze(records, { sourceName = "input", sourceText = "" } = {}) {
  if (!Array.isArray(records) || records.length === 0) throw new Error("Input contains no records");
  const accepted = [], findings = [];
  records.forEach((record, index) => {
    const rowNumber = index + 1;
    if (!record || typeof record !== "object" || Array.isArray(record)) {
      findings.push(`record ${rowNumber}: expected an object`);
      return;
    }
    const missing = REQUIRED_FIELDS.filter(key =>
      !Object.hasOwn(record, key) || record[key] === null || record[key] === undefined || record[key] === ""
    );
    if (missing.length) {
      findings.push(`record ${rowNumber}: missing required field(s): ${missing.join(", ")}`);
      return;
    }
    const quantity = Number(record.quantity_kg);
    const reusable = Number(record.reusable_kg);
    if (!Number.isFinite(quantity) || !Number.isFinite(reusable)) {
      findings.push(`record ${rowNumber}: quantities must be finite numbers`);
      return;
    }
    if (quantity < 0 || reusable < 0) {
      findings.push(`record ${rowNumber}: negative quantities are not allowed`);
      return;
    }
    if (reusable > quantity) {
      findings.push(`record ${rowNumber}: reusable_kg exceeds quantity_kg`);
      return;
    }
    const material = String(record.material).trim();
    if (!material) {
      findings.push(`record ${rowNumber}: material name is blank`);
      return;
    }
    accepted.push({ material, quantity_kg: quantity, reusable_kg: reusable, source_record_number: rowNumber });
  });

  const totalInspected = accepted.reduce((total, row) => total + row.quantity_kg, 0);
  const totalReusable = accepted.reduce((total, row) => total + row.reusable_kg, 0);
  if (!Number.isFinite(totalInspected) || !Number.isFinite(totalReusable)) {
    throw new Error("Aggregate overflow: totals exceed the supported finite-number range");
  }

  // Independent verification: second-pass accumulation, not the reduce expressions above.
  let checkInspected = 0, checkReusable = 0;
  for (const row of accepted) {
    checkInspected += row.quantity_kg;
    checkReusable += row.reusable_kg;
  }
  const close = (a, b) => Math.abs(a - b) <= 1e-10 * Math.max(1, Math.abs(a), Math.abs(b));
  const verificationPassed = close(totalInspected, checkInspected) && close(totalReusable, checkReusable);
  const byMaterial = Object.create(null);
  for (const row of accepted) {
    byMaterial[row.material] ??= {
      quantity_kg: 0, reusable_kg: 0, record_count: 0,
      quantity_observations: [], reusable_observations: [], source_record_numbers: []
    };
    const group = byMaterial[row.material];
    group.quantity_kg += row.quantity_kg;
    group.reusable_kg += row.reusable_kg;
    group.record_count++;
    group.quantity_observations.push(row.quantity_kg);
    group.reusable_observations.push(row.reusable_kg);
    group.source_record_numbers.push(row.source_record_number);
  }
  const fraction = totalInspected === 0 ? null : totalReusable / totalInspected;
  const materialComparison = Object.entries(byMaterial).map(([material, group]) => ({
    material,
    record_count: group.record_count,
    inspected_kg: group.quantity_kg,
    reusable_kg: group.reusable_kg,
    recovery_rate: group.quantity_kg === 0 ? null : group.reusable_kg / group.quantity_kg,
    share_of_total_reusable: totalReusable === 0 ? null : group.reusable_kg / totalReusable,
    quantity_statistics: summarize(group.quantity_observations),
    reusable_statistics: summarize(group.reusable_observations),
    source_record_numbers: group.source_record_numbers
  })).sort((a, b) => b.inspected_kg - a.inspected_kg || a.material.localeCompare(b.material));

  return {
    engine: "Nine Builders Analysis Engine",
    version: "0.2.0",
    source: { filename: sourceName, sha256: sha256(sourceText) },
    units: { quantity: "kg", reusable: "kg" },
    quality: {
      input_records: records.length,
      accepted_records: accepted.length,
      rejected_records: records.length - accepted.length,
      findings
    },
    results: {
      total_inspected_kg: totalInspected,
      total_reusable_kg: totalReusable,
      reusable_fraction: fraction,
      reusable_percent: fraction === null ? null : fraction * 100,
      quantity_statistics: summarize(accepted.map(row => row.quantity_kg)),
      reusable_statistics: summarize(accepted.map(row => row.reusable_kg)),
      by_material: Object.fromEntries(Object.entries(byMaterial).map(([name, group]) => [name, {
        quantity_kg: group.quantity_kg,
        reusable_kg: group.reusable_kg,
        record_count: group.record_count
      }])),
      material_comparison: materialComparison
    },
    verification: {
      method: "independent second-pass accumulation",
      recomputed_inspected_kg: checkInspected,
      recomputed_reusable_kg: checkReusable,
      passed: verificationPassed,
      tolerance: "1e-10 relative-to-magnitude with 1e-10 absolute floor"
    },
    limitations: [
      "Input provenance is hashed but not independently authenticated.",
      "The second pass checks arithmetic consistency, not measurement truth.",
      "Descriptive statistics describe supplied records; they do not establish population representativeness.",
      "Sample standard deviation is descriptive and is not a confidence interval.",
      "Invalid records are excluded and listed in quality findings."
    ]
  };
}

export function createManifest(reportText, sourceText, reportName, sourceName) {
  return {
    report_filename: reportName,
    report_sha256: sha256(reportText),
    source_filename: sourceName,
    source_sha256: sha256(sourceText)
  };
}
