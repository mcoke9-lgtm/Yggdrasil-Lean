import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "../src/server.js";

async function withServer(run) {
  const server = createServer();
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  try { await run(`http://127.0.0.1:${address.port}`); }
  finally { await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())); }
}

test("local interface serves its page and static assets", async () => {
  await withServer(async base => {
    const page = await fetch(base);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /Analysis Engine/);
    const script = await fetch(base + "/app.js");
    assert.equal(script.status, 200);
    assert.match(script.headers.get("content-type"), /javascript/);
    assert.equal((await fetch(base + "/missing.js")).status, 404);
  });
});

test("analysis API accepts CSV and returns the engine report", async () => {
  await withServer(async base => {
    const response = await fetch(base + "/api/analyze", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ filename: "materials.csv", content: "material,quantity_kg,reusable_kg\nWood,10,7\n" })
    });
    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.report.results.total_inspected_kg, 10);
    assert.equal(payload.report.results.total_reusable_kg, 7);
    assert.equal(payload.report.source.filename, "materials.csv");
  });
});

test("analysis API accepts JSON records and preserves evidence metadata", async () => {
  await withServer(async base => {
    const response = await fetch(base + "/api/analyze", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ filename: "materials.json", content: JSON.stringify([{ material: "Steel", quantity_kg: 8, reusable_kg: 6, evidence_type: "reported_by_source" }]) })
    });
    const payload = await response.json();
    assert.equal(response.status, 200);
    assert.equal(payload.report.evidence.classification_counts.reported_by_source, 1);
  });
});

test("API rejects unsupported file types and malformed requests", async () => {
  await withServer(async base => {
    const unsupported = await fetch(base + "/api/analyze", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ filename: "notes.txt", content: "hello" })
    });
    assert.equal(unsupported.status, 400);
    const malformed = await fetch(base + "/api/analyze", {
      method: "POST", headers: { "content-type": "application/json" }, body: "{"
    });
    assert.equal(malformed.status, 400);
    const wrongType = await fetch(base + "/api/analyze", { method: "POST", body: "x" });
    assert.equal(wrongType.status, 415);
  });
});

test("server binds only when caller chooses loopback; response hardens browser headers", async () => {
  await withServer(async base => {
    const response = await fetch(base);
    assert.equal(response.headers.get("x-content-type-options"), "nosniff");
    assert.match(response.headers.get("content-security-policy"), /default-src 'self'/);
    assert.equal(response.headers.get("cache-control"), "no-store");
  });
});
