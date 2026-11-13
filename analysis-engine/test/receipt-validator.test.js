import test from "node:test";
import assert from "node:assert/strict";
import { validateExecutionReceipt } from "../src/receipt-validator.js";

const validReceipt = () => ({
  request_id: "request-001",
  execution_id: "execution-001",
  contract_version: "0.1.0",
  input_sha256: "a".repeat(64),
  operation: { name: "materials.recovery.summary", version: "1.0" },
  engine: { name: "Nine Builders Analysis Engine", version: "0.4.0" },
  environment: { runtime: "Node.js", runtime_version: "22.0.0", platform: "linux" },
  started_at: "2026-11-13T12:00:00Z",
  completed_at: "2026-11-13T12:00:01Z",
  outcome: "completed",
  verification: { status: "passed", checks: [{ name: "input_sha256", status: "passed" }, { name: "output_schema", status: "passed" }] },
  output_reference: "reports/request-001.json",
  limitations: []
});

test("accepts a complete successful execution receipt", () => assert.deepEqual(validateExecutionReceipt(validReceipt()), { valid: true, errors: [] }));
test("rejects non-object receipts", () => assert.equal(validateExecutionReceipt(null).valid, false));
test("requires request and execution identity", () => { const r=validReceipt(); r.request_id=""; r.execution_id=""; const e=validateExecutionReceipt(r).errors; assert.ok(e.some(x=>x.includes("request_id"))); assert.ok(e.some(x=>x.includes("execution_id"))); });
test("requires valid input fingerprint and engine version", () => { const r=validReceipt(); r.input_sha256="bad"; r.engine.version="latest"; const e=validateExecutionReceipt(r).errors; assert.ok(e.some(x=>x.includes("input_sha256"))); assert.ok(e.some(x=>x.includes("engine.version"))); });
test("requires timezone-qualified timestamps in chronological order", () => { const r=validReceipt(); r.started_at="2026-11-13T12:00:00Z"; r.completed_at="2026-11-12T12:00:00Z"; const e=validateExecutionReceipt(r).errors; assert.ok(e.some(x=>x.includes("started_at"))); assert.ok(e.some(x=>x.includes("must not precede"))); });
test("rejects unsupported outcomes and verification statuses", () => { const r=validReceipt(); r.outcome="success"; r.verification.status="unknown"; assert.equal(validateExecutionReceipt(r).valid,false); });
test("does not allow completed execution with incomplete verification", () => { const r=validReceipt(); r.verification.status="incomplete"; assert.ok(validateExecutionReceipt(r).errors.some(x=>x.includes("completed outcome requires"))); });
test("does not allow passed verification when a check did not pass", () => { const r=validReceipt(); r.verification.checks[1].status="not_run"; assert.ok(validateExecutionReceipt(r).errors.some(x=>x.includes("non-passing check"))); });
test("requires structured operation, environment, checks, and limitations", () => { const r=validReceipt(); r.operation={}; r.environment={}; r.verification.checks=[{}]; r.limitations=[""]; assert.equal(validateExecutionReceipt(r).valid,false); });
test("permits failed and incomplete receipts without claiming success", () => { const r=validReceipt(); r.outcome="incomplete"; r.verification.status="incomplete"; r.verification.checks[1].status="not_run"; assert.equal(validateExecutionReceipt(r).valid,true); });
