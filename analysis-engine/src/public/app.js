const fileInput = document.querySelector("#file-input");
const fileLabel = document.querySelector("#file-label");
const analyzeButton = document.querySelector("#analyze-button");
const downloadButton = document.querySelector("#download-button");
const status = document.querySelector("#status");
const results = document.querySelector("#results");
let reportData = null;

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function formatNumber(value, digits = 2) {
  return value === null || value === undefined ? "Unknown" :
    new Intl.NumberFormat(undefined, { maximumFractionDigits: digits }).format(value);
}
function metric(label, value, suffix = "") {
  const card = element("div", "metric");
  card.append(element("span", "metric-label", label), element("strong", "metric-value", value + suffix));
  return card;
}
function renderReport(report) {
  reportData = report;
  results.hidden = false;
  const summary = document.querySelector("#summary");
  summary.replaceChildren(
    metric("Inspected", formatNumber(report.results.total_inspected_kg), " kg"),
    metric("Reusable", formatNumber(report.results.total_reusable_kg), " kg"),
    metric("Recovery rate", formatNumber(report.results.reusable_percent), "%"),
    metric("Accepted records", formatNumber(report.quality.accepted_records, 0))
  );

  const evidence = document.querySelector("#evidence");
  evidence.replaceChildren();
  const counts = report.evidence.classification_counts;
  for (const [name, count] of Object.entries(counts)) {
    const line = element("div", "evidence-line");
    line.append(element("span", "", name.replaceAll("_", " ")), element("strong", "", String(count)));
    evidence.append(line);
  }
  evidence.append(element("p", "caveat", report.evidence.note));

  const materials = document.querySelector("#materials");
  materials.replaceChildren();
  for (const item of report.results.material_comparison) {
    const tr = document.createElement("tr");
    for (const value of [
      item.material, formatNumber(item.inspected_kg) + " kg",
      formatNumber(item.reusable_kg) + " kg",
      item.recovery_rate === null ? "Unknown" : formatNumber(item.recovery_rate * 100) + "%",
      item.source_record_numbers.join(", ")
    ]) tr.append(element("td", "", value));
    materials.append(tr);
  }

  const uncertainty = document.querySelector("#uncertainty");
  uncertainty.replaceChildren();
  uncertainty.append(element("p", "status-chip", report.uncertainty.status.replaceAll("_", " ")));
  uncertainty.append(element("p", "", report.uncertainty.interpretation));
  if (report.uncertainty.inspected_kg) {
    uncertainty.append(element("p", "", `Inspected range: ${formatNumber(report.uncertainty.inspected_kg.min)}–${formatNumber(report.uncertainty.inspected_kg.max)} kg`));
    uncertainty.append(element("p", "", `Reusable range: ${formatNumber(report.uncertainty.reusable_kg.min)}–${formatNumber(report.uncertainty.reusable_kg.max)} kg`));
    const ratio = report.uncertainty.recovery_fraction;
    uncertainty.append(element("p", "", `Conservative recovery range: ${formatNumber(ratio.min * 100)}–${formatNumber(ratio.max * 100)}%`));
  }
  if (report.uncertainty.assumptions) {
    const list = element("ul");
    for (const assumption of report.uncertainty.assumptions) list.append(element("li", "", assumption));
    uncertainty.append(list);
  }

  const quality = document.querySelector("#quality");
  quality.replaceChildren();
  quality.append(element("p", "", `Input: ${report.quality.input_records} · Accepted: ${report.quality.accepted_records} · Rejected: ${report.quality.rejected_records}`));
  quality.append(element("p", "status-chip", report.verification.passed ? "Arithmetic check passed" : "Arithmetic check failed"));
  if (report.quality.findings.length) {
    const list = element("ul");
    for (const finding of report.quality.findings) list.append(element("li", "", finding));
    quality.append(list);
  } else quality.append(element("p", "caveat", "No data-quality findings."));
  document.querySelector("#raw-report").textContent = JSON.stringify(report, null, 2);
}

fileInput.addEventListener("change", () => {
  const file = fileInput.files?.[0];
  fileLabel.textContent = file ? file.name : "Choose CSV or JSON";
  analyzeButton.disabled = !file;
  status.textContent = file ? `${file.name} selected · ${formatNumber(file.size, 0)} bytes` : "Choose a file to begin.";
});
analyzeButton.addEventListener("click", async () => {
  const file = fileInput.files?.[0];
  if (!file) return;
  analyzeButton.disabled = true;
  status.textContent = "Reading and analyzing locally…";
  try {
    const content = await file.text();
    const response = await fetch("/api/analyze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename: file.name, content })
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.error || "Analysis failed");
    renderReport(payload.report);
    status.textContent = "Analysis complete. The file was processed by the local server.";
    results.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    status.textContent = error.message;
    results.hidden = true;
  } finally {
    analyzeButton.disabled = false;
  }
});
downloadButton.addEventListener("click", () => {
  if (!reportData) return;
  const blob = new Blob([JSON.stringify(reportData, null, 2) + "\n"], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "nine-builders-analysis-report.json";
  link.click();
  URL.revokeObjectURL(url);
});
