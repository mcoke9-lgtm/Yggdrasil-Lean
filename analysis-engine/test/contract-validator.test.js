import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { validateRequest, verifyInputBytes } from "../src/contract-validator.js";

const bytes = Buffer.from("material,quantity_kg,reusable_kg\nWood,10,8\n", "utf8");
const digest = createHash("sha256").update(bytes).digest("hex");

function validRequest() {
  return {
    contract: { name: "Portable Analysis Contract", version: "0.1.0" },
    request: { request_id: "validator-test-001", operation: "materials.recovery.summary", operation_version: "1.0" },
    input: { format: "csv", encoding: "UTF-8", filename: "materials.csv", sha256: digest, units: { quantity: "kg", reusable: "kg" } },
    engine_requirement: { name: "Nine Builders Analysis Engine", minimum_version: "0.4.0" },
    expected_output: { schema: "materials.recovery.report", schema_version: "1.0", verification_required: true }
  };
}

test("accepts a complete version 0.1.0 request", () => {
  assert.deepEqual(validateRequest(validRequest()), { valid: true, errors: [] });
});

test("rejects unsupported contract and operation versions", () => {
  const request = validRequest();
  request.contract.version = "0.2.0";
  request.request.operation_version = "2.0";
  const result = validateRequest(request);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some(error => error.includes("contract.version")));
  assert.ok(result.errors.some(error => error.includes("operation_version")));
});

test("rejects missing request identity and malformed SHA-256", () => {
  const request = validRequest();
  request.request.request_id = " ";
  request.input.sha256 = "abc";
  const result = validateRequest(request);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some(error => error.includes("request_id")));
  assert.ok(result.errors.some(error => error.includes("SHA-256")));
});

test("rejects unsupported format, encoding, units and output schema", () => {
  const request = validRequest();
  request.input.format = "xlsx";
  request.input.encoding = "UTF-16";
  request.input.units.quantity = "lb";
  request.expected_output.schema_version = "2.0";
  const result = validateRequest(request);
  assert.equal(result.valid, false);
  assert.equal(result.errors.length, 4);
});

test("rejects malformed engine minimum version and non-boolean verification flag", () => {
  const request = validRequest();
  request.engine_requirement.minimum_version = "v0.4";
  request.expected_output.verification_required = "yes";
  const result = validateRequest(request);
  assert.equal(result.valid, false);
  assert.equal(result.errors.length, 2);
});

test("rejects arrays and null where request objects are required", () => {
  assert.equal(validateRequest(null).valid, false);
  const request = validRequest();
  request.input.units = [];
  assert.ok(validateRequest(request).errors.some(error => error.includes("input.units")));
});

test("verifies exact source bytes against the request hash", () => {
  assert.deepEqual(verifyInputBytes(validRequest(), bytes), {
    valid: true, matched: true, expected: digest, actual: digest, errors: []
  });
});

test("detects changed source bytes", () => {
  const result = verifyInputBytes(validRequest(), Buffer.from("different", "utf8"));
  assert.equal(result.valid, false);
  assert.equal(result.matched, false);
  assert.ok(result.errors.some(error => error.includes("does not match")));
});

test("does not hash input bytes when the request itself is invalid", () => {
  const request = validRequest();
  request.input.sha256 = "not-a-hash";
  const result = verifyInputBytes(request, bytes);
  assert.equal(result.valid, false);
  assert.equal(result.actual, null);
});

test("rejects non-byte input to the hash verifier", () => {
  const result = verifyInputBytes(validRequest(), "not bytes");
  assert.equal(result.valid, false);
  assert.equal(result.actual, null);
});
