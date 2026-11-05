import { readFile, writeFile, mkdir } from "node:fs/promises";
import { basename, dirname, extname } from "node:path";
import { analyze, parseInput, createManifest } from "./engine.js";

const [inputPath, outputPath = "analysis-report.json"] = process.argv.slice(2);
if (!inputPath) {
  console.error("Usage: node src/cli.js <input.csv|input.json> [report.json]");
  process.exit(64);
}
try {
  const sourceText = await readFile(inputPath, "utf8");
  const extension = extname(inputPath).toLowerCase().slice(1);
  const records = parseInput(sourceText, extension);
  const report = analyze(records, { sourceName: basename(inputPath), sourceText });
  const reportText = JSON.stringify(report, null, 2) + "\n";
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, reportText, { flag: "wx" });
  const manifest = createManifest(reportText, sourceText, basename(outputPath), basename(inputPath));
  await writeFile(outputPath + ".manifest.json", JSON.stringify(manifest, null, 2) + "\n", { flag: "wx" });
  console.log(JSON.stringify({
    report: outputPath,
    accepted: report.quality.accepted_records,
    rejected: report.quality.rejected_records,
    verification_passed: report.verification.passed
  }, null, 2));
  if (!report.verification.passed) process.exitCode = 2;
} catch (error) {
  console.error(`Analysis failed: ${error.message}`);
  process.exitCode = 1;
}
