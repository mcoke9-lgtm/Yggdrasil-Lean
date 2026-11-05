import test from "node:test";
import assert from "node:assert/strict";
import { readSecret } from "../src/environment-credentials.js";

test("credential resolver reads only the exact configured secret reference", async () => {
  const name = "BUILDER_SECRET_NINE_BUILDERS_ODIN";
  const previous = process.env[name];
  process.env[name] = "local-test-token";
  try {
    assert.equal(await readSecret("nine-builders/odin"), "local-test-token");
    await assert.rejects(() => readSecret("nine-builders/missing"), /No operator credential is configured/);
    await assert.rejects(() => readSecret("../ODIN"), /unsupported characters/);
  } finally {
    if (previous === undefined) delete process.env[name];
    else process.env[name] = previous;
  }
});
