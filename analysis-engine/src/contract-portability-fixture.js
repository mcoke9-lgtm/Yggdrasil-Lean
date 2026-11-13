import { createHash } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { executeContract } from "./contract-runner.js";

const records = [
  { material: "Wood", quantity_kg: 10, reusable_kg: 8, evidence_type: "directly_measured" },
  { material: "Metal", quantity_kg: 5, reusable_kg: 4, evidence_type: "reported_by_source" }
];
const inputs = {
  csv: Buffer.from("material,quantity_kg,reusable_kg,evidence_type\nWood,10,8,directly_measured\nMetal,5,4,reported_by_source\n", "utf8"),
  json: Buffer.from(JSON.stringify(records), "utf8")
};
const results = {};
for (const [format, bytes] of Object.entries(inputs)) {
  const request = {
    contract: { name: "Portable Analysis Contract", version: "0.1.0" },
    request: { request_id: `portability-${format}-001`, operation: "materials.recovery.summary", operation_version: "1.0" },
    input: {
      format, encoding: "UTF-8", filename: `materials.${format}`,
      sha256: createHash("sha256").update(bytes).digest("hex"),
      units: { quantity: "kg", reusable: "kg" }
    },
    engine_requirement: { name: "Nine Builders Analysis Engine", minimum_version: "0.4.0" },
    expected_output: { schema: "materials.recovery.report", schema_version: "1.0", verification_required: true }
  };
  const result = executeContract(request, bytes);
  if (result.outcome !== "completed" || !result.verification.valid || result.errors.length) {
    throw new Error(`${format} portability fixture failed: ${result.errors.join("; ")}`);
  }
  results[format] = {
    source_bytes_sha256: createHash("sha256").update(bytes).digest("hex"),
    outcome: result.outcome,
    report: result.report,
    receipt: result.receipt,
    verification: result.verification
  };
}
const outputPath = process.argv[2] ?? "portability-results.json";
await writeFile(outputPath, JSON.stringify({ node_version: process.versions.node, platform: process.platform, results }, null, 2) + "\n");
process.stdout.write(`Wrote portability results for Node.js ${process.versions.node} to ${outputPath}\n`);
