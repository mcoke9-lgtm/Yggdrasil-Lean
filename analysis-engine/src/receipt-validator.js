const obj = v => v !== null && typeof v === "object" && !Array.isArray(v);
const str = v => typeof v === "string" && v.trim().length > 0;
const hash = v => typeof v === "string" && /^[a-f0-9]{64}$/.test(v);
const isoDate = v => typeof v === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(v) && Number.isFinite(Date.parse(v));
const outcomes = ["completed", "rejected", "failed", "incomplete"];
const verificationStatuses = ["passed", "failed", "incomplete", "unsupported"];

/**
 * Validate an execution receipt against Portable Analysis Contract 001 §9.
 * A structurally valid receipt is a runner-supplied record, not proof of trust.
 */
export function validateExecutionReceipt(receipt) {
  const errors = [];
  if (!obj(receipt)) return { valid: false, errors: ["receipt must be an object"] };
  if (!str(receipt.request_id)) errors.push("receipt.request_id must be non-empty");
  if (!str(receipt.execution_id)) errors.push("receipt.execution_id must be non-empty");
  if (!str(receipt.contract_version) || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(receipt.contract_version)) errors.push("receipt.contract_version must be a semantic version");
  if (!hash(receipt.input_sha256)) errors.push("receipt.input_sha256 must be a lowercase SHA-256 digest");
  if (!obj(receipt.operation)) errors.push("receipt.operation must be an object");
  else {
    if (!str(receipt.operation.name)) errors.push("receipt.operation.name must be non-empty");
    if (!str(receipt.operation.version)) errors.push("receipt.operation.version must be non-empty");
  }
  if (!obj(receipt.engine)) errors.push("receipt.engine must be an object");
  else {
    if (!str(receipt.engine.name)) errors.push("receipt.engine.name must be non-empty");
    if (!str(receipt.engine.version) || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(receipt.engine.version)) errors.push("receipt.engine.version must be a semantic version");
  }
  if (!obj(receipt.environment)) errors.push("receipt.environment must be an object");
  else {
    if (!str(receipt.environment.runtime)) errors.push("receipt.environment.runtime must be non-empty");
    if (!str(receipt.environment.runtime_version)) errors.push("receipt.environment.runtime_version must be non-empty");
    if (receipt.environment.platform !== undefined && !str(receipt.environment.platform)) errors.push("receipt.environment.platform must be non-empty when supplied");
  }
  if (!isoDate(receipt.started_at)) errors.push("receipt.started_at must be an ISO-8601 timestamp with timezone");
  if (!isoDate(receipt.completed_at)) errors.push("receipt.completed_at must be an ISO-8601 timestamp with timezone");
  if (isoDate(receipt.started_at) && isoDate(receipt.completed_at) && Date.parse(receipt.completed_at) < Date.parse(receipt.started_at)) errors.push("receipt.completed_at must not precede started_at");
  if (!outcomes.includes(receipt.outcome)) errors.push("receipt.outcome is unsupported");
  if (!obj(receipt.verification)) errors.push("receipt.verification must be an object");
  else {
    if (!verificationStatuses.includes(receipt.verification.status)) errors.push("receipt.verification.status is unsupported");
    if (!Array.isArray(receipt.verification.checks) || !receipt.verification.checks.every(c => obj(c) && str(c.name) && ["passed","failed","incomplete","unsupported","not_run"].includes(c.status))) errors.push("receipt.verification.checks must contain named checks with supported statuses");
  }
  if (receipt.output_reference !== null && receipt.output_reference !== undefined && !str(receipt.output_reference)) errors.push("receipt.output_reference must be non-empty when supplied");
  if (!Array.isArray(receipt.limitations) || !receipt.limitations.every(str)) errors.push("receipt.limitations must be an array of non-empty strings");
  if (receipt.outcome === "completed" && receipt.verification?.status !== "passed") errors.push("completed outcome requires passed verification");
  if (receipt.verification?.status === "passed" && Array.isArray(receipt.verification.checks) && receipt.verification.checks.some(c => c.status !== "passed")) errors.push("passed verification cannot contain a non-passing check");
  return { valid: errors.length === 0, errors };
}
