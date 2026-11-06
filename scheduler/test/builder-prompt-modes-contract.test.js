import test from "node:test";
import assert from "node:assert/strict";
import { buildBuilderModeInstructions } from "../src/builder-prompt-modes-contract.js";
test("ordinary tasks do not activate special modes", () => {
  assert.equal(buildBuilderModeInstructions("ODIN", "ordinary task", []), "");
});
