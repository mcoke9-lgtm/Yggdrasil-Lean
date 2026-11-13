import { OUTPUT_SCHEMA, OUTPUT_SCHEMA_VERSION } from "./contract-validator.js";
const EVIDENCE = ["directly_measured", "reported_by_source", "estimated", "modeled", "unknown"];
const obj = v => v !== null && typeof v === "object" && !Array.isArray(v);
const str = v => typeof v === "string" && v.trim().length > 0;
const num = v => typeof v === "number" && Number.isFinite(v);
const nn = v => num(v) && v >= 0;
const int = v => Number.isInteger(v) && v >= 0;
const frac = v => v === null || (num(v) && v >= 0 && v <= 1);
const hash = v => typeof v === "string" && /^[a-f0-9]{64}$/.test(v);

export function validateReport(report, { schema = OUTPUT_SCHEMA, schemaVersion = OUTPUT_SCHEMA_VERSION } = {}) {
  const errors = [];
  const object = (v, p) => { if (!obj(v)) { errors.push(`${p} must be an object`); return false; } return true; };
  const stats = (v, p) => {
    if (!object(v, p)) return;
    if (!int(v.count)) errors.push(`${p}.count must be a non-negative integer`);
    for (const k of ["sum", "mean", "median", "min", "max", "sample_standard_deviation"])
      if (v[k] !== null && !num(v[k])) errors.push(`${p}.${k} must be finite or null`);
  };
  if (!object(report, "report")) return { valid: false, errors };
  if (schema !== OUTPUT_SCHEMA) errors.push("unsupported output schema");
  if (schemaVersion !== OUTPUT_SCHEMA_VERSION) errors.push("unsupported output schema version");
  if (!str(report.engine)) errors.push("report.engine must be a non-empty string");
  if (typeof report.version !== "string" || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(report.version)) errors.push("report.version must be a semantic version");
  if (object(report.source, "report.source")) {
    if (!str(report.source.filename)) errors.push("report.source.filename must be a non-empty string");
    if (!hash(report.source.sha256)) errors.push("report.source.sha256 must be a lowercase SHA-256 digest");
  }
  if (object(report.units, "report.units")) {
    if (report.units.quantity !== "kg") errors.push('report.units.quantity must be "kg"');
    if (report.units.reusable !== "kg") errors.push('report.units.reusable must be "kg"');
  }
  if (object(report.quality, "report.quality")) {
    for (const k of ["input_records", "accepted_records", "rejected_records"])
      if (!int(report.quality[k])) errors.push(`report.quality.${k} must be a non-negative integer`);
    if (!Array.isArray(report.quality.findings) || !report.quality.findings.every(str)) errors.push("report.quality.findings must be an array of non-empty strings");
    const q = report.quality;
    if (int(q.input_records) && int(q.accepted_records) && int(q.rejected_records) && q.input_records !== q.accepted_records + q.rejected_records)
      errors.push("report.quality accepted and rejected counts must sum to input_records");
  }
  if (object(report.evidence, "report.evidence")) {
    if (object(report.evidence.classification_counts, "report.evidence.classification_counts")) {
      const counts = report.evidence.classification_counts;
      for (const t of EVIDENCE) if (!int(counts[t])) errors.push(`report.evidence.classification_counts.${t} must be a non-negative integer`);
      if (EVIDENCE.every(t => int(counts[t])) && int(report.quality?.accepted_records) &&
          EVIDENCE.reduce((s,t) => s + counts[t], 0) !== report.quality.accepted_records)
        errors.push("report.evidence classification counts must sum to accepted_records");
    }
    if (!Array.isArray(report.evidence.references)) errors.push("report.evidence.references must be an array");
    else report.evidence.references.forEach((r,i) => {
      const p = `report.evidence.references[${i}]`;
      if (!object(r,p)) return;
      if (!Number.isInteger(r.source_record_number) || r.source_record_number < 1) errors.push(`${p}.source_record_number must be positive`);
      if (!EVIDENCE.includes(r.evidence_type)) errors.push(`${p}.evidence_type is unsupported`);
      if (!str(r.source_reference)) errors.push(`${p}.source_reference must be non-empty`);
    });
    if (!str(report.evidence.note)) errors.push("report.evidence.note must be non-empty");
  }
  if (object(report.results, "report.results")) {
    const r = report.results;
    for (const k of ["total_inspected_kg", "total_reusable_kg"]) if (!nn(r[k])) errors.push(`report.results.${k} must be finite and non-negative`);
    if (!frac(r.reusable_fraction)) errors.push("report.results.reusable_fraction must be 0..1 or null");
    if (r.reusable_percent !== null && (!num(r.reusable_percent) || r.reusable_percent < 0 || r.reusable_percent > 100)) errors.push("report.results.reusable_percent must be 0..100 or null");
    stats(r.quantity_statistics, "report.results.quantity_statistics");
    stats(r.reusable_statistics, "report.results.reusable_statistics");
    if (object(r.by_material, "report.results.by_material")) for (const [name,m] of Object.entries(r.by_material)) {
      const p = `report.results.by_material.${name}`;
      if (!str(name)) errors.push("report.results.by_material keys must be non-empty");
      if (!object(m,p)) continue;
      for (const k of ["quantity_kg","reusable_kg"]) if (!nn(m[k])) errors.push(`${p}.${k} must be finite and non-negative`);
      if (!int(m.record_count)) errors.push(`${p}.record_count must be a non-negative integer`);
    }
    if (!Array.isArray(r.material_comparison)) errors.push("report.results.material_comparison must be an array");
    else r.material_comparison.forEach((m,i) => {
      const p = `report.results.material_comparison[${i}]`;
      if (!object(m,p)) return;
      if (!str(m.material)) errors.push(`${p}.material must be non-empty`);
      if (!int(m.record_count)) errors.push(`${p}.record_count must be a non-negative integer`);
      for (const k of ["inspected_kg","reusable_kg"]) if (!nn(m[k])) errors.push(`${p}.${k} must be finite and non-negative`);
      if (!frac(m.recovery_rate) || !frac(m.share_of_total_reusable)) errors.push(`${p} rates must be 0..1 or null`);
      stats(m.quantity_statistics, `${p}.quantity_statistics`);
      stats(m.reusable_statistics, `${p}.reusable_statistics`);
      if (!Array.isArray(m.source_record_numbers) || !m.source_record_numbers.every(n => Number.isInteger(n) && n > 0)) errors.push(`${p}.source_record_numbers must contain positive integers`);
    });
  }
  if (object(report.uncertainty, "report.uncertainty")) {
    const u = report.uncertainty;
    if (!["complete_record_bounds","incomplete_record_bounds"].includes(u.status)) errors.push("report.uncertainty.status is unsupported");
    for (const k of ["records_with_bounds","accepted_records"]) if (!int(u[k])) errors.push(`report.uncertainty.${k} must be a non-negative integer`);
    if (!str(u.interpretation)) errors.push("report.uncertainty.interpretation must be non-empty");
    for (const k of ["inspected_kg","reusable_kg","recovery_fraction"]) if (u[k] !== null && u[k] !== undefined) {
      const p = `report.uncertainty.${k}`;
      if (object(u[k],p) && (!num(u[k].min) || !num(u[k].max) || u[k].min > u[k].max)) errors.push(`${p} must have ordered finite min/max`);
    }
    if (u.assumptions !== undefined && (!Array.isArray(u.assumptions) || !u.assumptions.every(str))) errors.push("report.uncertainty.assumptions must be non-empty strings");
  }
  if (object(report.verification, "report.verification")) {
    const v = report.verification;
    if (!str(v.method)) errors.push("report.verification.method must be non-empty");
    for (const k of ["recomputed_inspected_kg","recomputed_reusable_kg"]) if (!nn(v[k])) errors.push(`report.verification.${k} must be finite and non-negative`);
    if (typeof v.passed !== "boolean") errors.push("report.verification.passed must be boolean");
    if (!str(v.tolerance)) errors.push("report.verification.tolerance must be non-empty");
  }
  if (!Array.isArray(report.limitations) || !report.limitations.every(str)) errors.push("report.limitations must be non-empty strings");
  return { valid: errors.length === 0, errors };
}
