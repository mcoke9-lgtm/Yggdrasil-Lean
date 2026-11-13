import { createHash } from "node:crypto";

export const CONTRACT_NAME = "Portable Analysis Contract";
export const CONTRACT_VERSION = "0.1.0";
export const SUPPORTED_OPERATION = "materials.recovery.summary";
export const SUPPORTED_OPERATION_VERSION = "1.0";
export const OUTPUT_SCHEMA = "materials.recovery.report";
export const OUTPUT_SCHEMA_VERSION = "1.0";

const isObject = value => value !== null && typeof value === "object" && !Array.isArray(value);
const nonEmptyString = value => typeof value === "string" && value.trim().length > 0;
const semver = value => typeof value === "string" && /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(value);

export function validateRequest(request) {
  const errors = [];
  const requireObject = (value, path) => {
    if (!isObject(value)) {
      errors.push(`${path} must be an object`);
      return false;
    }
    return true;
  };

  if (!requireObject(request, "request")) return { valid: false, errors };

  if (!requireObject(request.contract, "contract")) {
    // Continue collecting the remaining top-level structural errors.
  } else {
    if (request.contract.name !== CONTRACT_NAME) errors.push(`contract.name must be "${CONTRACT_NAME}"`);
    if (request.contract.version !== CONTRACT_VERSION) errors.push(`contract.version must be "${CONTRACT_VERSION}"`);
  }

  if (!requireObject(request.request, "request.request")) {
    // no nested fields can be inspected
  } else {
    if (!nonEmptyString(request.request.request_id)) errors.push("request.request_id must be a non-empty string");
    if (request.request.operation !== SUPPORTED_OPERATION) errors.push(`request.operation must be "${SUPPORTED_OPERATION}"`);
    if (request.request.operation_version !== SUPPORTED_OPERATION_VERSION) errors.push(`request.operation_version must be "${SUPPORTED_OPERATION_VERSION}"`);
  }

  if (!requireObject(request.input, "input")) {
    // no nested fields can be inspected
  } else {
    if (!["csv", "json"].includes(request.input.format)) errors.push('input.format must be "csv" or "json"');
    if (request.input.encoding !== "UTF-8") errors.push('input.encoding must be "UTF-8"');
    if (!nonEmptyString(request.input.filename)) errors.push("input.filename must be a non-empty string");
    if (typeof request.input.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(request.input.sha256)) {
      errors.push("input.sha256 must be a 64-character lowercase hexadecimal SHA-256 digest");
    }
    if (!isObject(request.input.units)) {
      errors.push("input.units must be an object");
    } else {
      if (request.input.units.quantity !== "kg") errors.push('input.units.quantity must be "kg"');
      if (request.input.units.reusable !== "kg") errors.push('input.units.reusable must be "kg"');
    }
  }

  if (!requireObject(request.engine_requirement, "engine_requirement")) {
    // no nested fields can be inspected
  } else {
    if (!nonEmptyString(request.engine_requirement.name)) errors.push("engine_requirement.name must be a non-empty string");
    if (!semver(request.engine_requirement.minimum_version)) errors.push("engine_requirement.minimum_version must be a semantic version (major.minor.patch)");
  }

  if (!requireObject(request.expected_output, "expected_output")) {
    // no nested fields can be inspected
  } else {
    if (request.expected_output.schema !== OUTPUT_SCHEMA) errors.push(`expected_output.schema must be "${OUTPUT_SCHEMA}"`);
    if (request.expected_output.schema_version !== OUTPUT_SCHEMA_VERSION) errors.push(`expected_output.schema_version must be "${OUTPUT_SCHEMA_VERSION}"`);
    if (typeof request.expected_output.verification_required !== "boolean") errors.push("expected_output.verification_required must be a boolean");
  }

  return { valid: errors.length === 0, errors };
}

export function verifyInputBytes(request, bytes) {
  const requestCheck = validateRequest(request);
  if (!requestCheck.valid) {
    return { valid: false, matched: false, expected: request?.input?.sha256 ?? null, actual: null, errors: requestCheck.errors };
  }
  if (!(bytes instanceof Uint8Array)) {
    return { valid: false, matched: false, expected: request.input.sha256, actual: null, errors: ["input bytes must be a Uint8Array or Buffer"] };
  }
  const actual = createHash("sha256").update(bytes).digest("hex");
  const matched = actual === request.input.sha256;
  return {
    valid: matched,
    matched,
    expected: request.input.sha256,
    actual,
    errors: matched ? [] : ["input SHA-256 does not match the request"]
  };
}
