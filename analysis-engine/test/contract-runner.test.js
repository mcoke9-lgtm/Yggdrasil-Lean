import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { executeContract } from "../src/contract-runner.js";
const bytes = Buffer.from("material,quantity_kg,reusable_kg,evidence_type\nWood,10,8,directly_measured\n","utf8");
const request = () => ({
  contract:{name:"Portable Analysis Contract",version:"0.1.0"},
  request:{request_id:"runner-test-001",operation:"materials.recovery.summary",operation_version:"1.0"},
  input:{format:"csv",encoding:"UTF-8",filename:"materials.csv",sha256:createHash("sha256").update(bytes).digest("hex"),units:{quantity:"kg",reusable:"kg"}},
  engine_requirement:{name:"Nine Builders Analysis Engine",minimum_version:"0.4.0"},
  expected_output:{schema:"materials.recovery.report",schema_version:"1.0",verification_required:true}
});

test("executes a valid request and returns a report plus a valid successful receipt",()=>{
  const result=executeContract(request(),bytes);
  assert.equal(result.outcome,"completed");
  assert.equal(result.report.results.total_inspected_kg,10);
  assert.equal(result.report.results.total_reusable_kg,8);
  assert.equal(result.receipt.verification.status,"passed");
  assert.equal(result.verification.valid,true);
  assert.deepEqual(result.errors,[]);
});

test("rejects input bytes whose hash differs from the request",()=>{
  const result=executeContract(request(),Buffer.from("different"));
  assert.equal(result.outcome,"rejected");
  assert.equal(result.report,null);
  assert.equal(result.receipt.verification.status,"failed");
});

test("rejects malformed requests without attempting analysis",()=>{
  const req=request(); req.contract.version="9.0.0";
  const result=executeContract(req,bytes);
  assert.equal(result.outcome,"rejected");
  assert.equal(result.report,null);
});

test("rejects invalid UTF-8 bytes even when their digest matches",()=>{
  const invalid=Buffer.from([0xff,0xfe,0xfd]);
  const req=request(); req.input.sha256=createHash("sha256").update(invalid).digest("hex");
  const result=executeContract(req,invalid);
  assert.equal(result.outcome,"failed");
  assert.ok(result.errors.some(e=>e.toLowerCase().includes("encoded data")));
});

test("rejects an unsupported engine requirement",()=>{
  const req=request(); req.engine_requirement.minimum_version="9.0.0";
  const result=executeContract(req,bytes);
  assert.equal(result.outcome,"rejected");
  assert.equal(result.receipt.verification.status,"unsupported");
});
