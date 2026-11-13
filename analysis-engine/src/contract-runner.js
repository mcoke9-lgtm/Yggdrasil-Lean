import { createHash, randomUUID } from "node:crypto";
import { TextDecoder } from "node:util";
import { analyze, parseInput } from "./engine.js";
import { validateRequest, verifyInputBytes } from "./contract-validator.js";
import { validateReport } from "./report-validator.js";
import { validateExecutionReceipt } from "./receipt-validator.js";

const ENGINE_NAME = "Nine Builders Analysis Engine";
const ENGINE_VERSION = "0.4.0";
const semverParts = value => String(value).split("-")[0].split(".").map(Number);
const atLeast = (actual, minimum) => {
  const a = semverParts(actual), m = semverParts(minimum);
  for (let i=0;i<3;i++) if (a[i] !== m[i]) return a[i] > m[i];
  return true;
};

/**
 * Execute a validated Portable Analysis Contract request against exact input bytes.
 * Returns report, receipt, and validation details; does not persist or transmit data.
 */
export function executeContract(request, inputBytes, { platform = process.platform } = {}) {
  const startedAt = new Date().toISOString();
  const executionId = randomUUID();
  const errors = [];
  const requestCheck = validateRequest(request);
  let report = null;
  let outcome = "failed";
  let verificationStatus = "incomplete";
  let checks = [];
  let outputReference = null;
  const inputDigest = inputBytes instanceof Uint8Array
    ? createHash("sha256").update(inputBytes).digest("hex") : null;

  if (!requestCheck.valid) {
    errors.push(...requestCheck.errors);
    outcome = "rejected";
    verificationStatus = "failed";
    checks = [{name:"request_validation",status:"failed"}];
  } else {
    const inputCheck = verifyInputBytes(request, inputBytes);
    checks.push({name:"request_validation",status:"passed"});
    checks.push({name:"input_sha256",status:inputCheck.matched ? "passed" : "failed"});
    if (!inputCheck.matched) {
      errors.push(...inputCheck.errors);
      outcome = "rejected";
      verificationStatus = "failed";
    } else if (request.engine_requirement.name !== ENGINE_NAME || !atLeast(ENGINE_VERSION, request.engine_requirement.minimum_version)) {
      errors.push("available engine does not satisfy the requested engine requirement");
      outcome = "rejected";
      verificationStatus = "unsupported";
      checks.push({name:"engine_compatibility",status:"unsupported"});
    } else {
      checks.push({name:"engine_compatibility",status:"passed"});
      try {
        const text = new TextDecoder("utf-8", {fatal:true}).decode(inputBytes);
        const records = parseInput(text, request.input.format);
        report = analyze(records, {sourceName:request.input.filename,sourceText:text});
        const reportCheck = validateReport(report, {
          schema:request.expected_output.schema,
          schemaVersion:request.expected_output.schema_version
        });
        checks.push({name:"output_schema",status:reportCheck.valid ? "passed" : "failed"});
        if (!reportCheck.valid) errors.push(...reportCheck.errors);
        checks.push({name:"engine_arithmetic_verification",status:report.verification.passed ? "passed" : "failed"});
        if (!report.verification.passed) errors.push("engine arithmetic verification failed");
        if (reportCheck.valid && report.verification.passed) {
          outcome = "completed";
          verificationStatus = "passed";
        } else {
          outcome = "failed";
          verificationStatus = "failed";
        }
      } catch (error) {
        errors.push(error.message);
        outcome = "failed";
        verificationStatus = "failed";
        checks.push({name:"analysis_execution",status:"failed"});
      }
    }
  }

  const completedAt = new Date().toISOString();
  const receipt = {
    request_id: request?.request?.request_id ?? "invalid-request",
    execution_id: executionId,
    contract_version: request?.contract?.version ?? "unknown",
    input_sha256: inputDigest ?? "0".repeat(64),
    operation: {
      name: request?.request?.operation ?? "unknown",
      version: request?.request?.operation_version ?? "unknown"
    },
    engine: {name:ENGINE_NAME,version:ENGINE_VERSION},
    environment: {runtime:"Node.js",runtime_version:process.versions.node,platform},
    started_at:startedAt,
    completed_at:completedAt,
    outcome,
    verification:{status:verificationStatus,checks},
    output_reference:outputReference,
    limitations:[
      "The receipt is generated locally by the runner and is not authenticated or signed.",
      "The runner validates report structure and arithmetic checks but does not authenticate source evidence.",
      "No output persistence or network transport is performed."
    ]
  };
  const receiptCheck = validateExecutionReceipt(receipt);
  if (!receiptCheck.valid) errors.push(...receiptCheck.errors);
  return {
    outcome,
    report,
    receipt,
    verification:{status:verificationStatus,checks,valid:receiptCheck.valid},
    input_sha256:inputDigest,
    errors
  };
}
