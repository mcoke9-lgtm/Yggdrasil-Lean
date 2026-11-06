import test from "node:test";
import assert from "node:assert/strict";
import { buildBuilderModeInstructions } from "../src/builder-prompt-modes-contract.js";

test("ordinary tasks do not activate special modes", () => {
  assert.equal(buildBuilderModeInstructions("ODIN", "ordinary task", []), "");
});

test("difficult-spaces mode is triggered by the task prompt", () => {
  const result = buildBuilderModeInstructions(
    "KVASIR", "NAVIGATING_DIFFICULT_SPACES_MODE\nInvestigate safely.", []
  );
  assert.match(result, /LOWER FIRA \/ THURISAZ/);
  assert.match(result, /UPPER FIRA \/ ALGIZ/);
  assert.match(result, /WAIT_FOR_DEPENDENCY/);
  assert.match(result, /RESPECT_REFUSAL/);
  assert.match(result, /Never ask the user to paste secrets/);
});

test("review mode rejects an incident not recorded in this run", () => {
  const prompt = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident-1";
  assert.throws(
    () => buildBuilderModeInstructions("BALDR", prompt, []),
    /recorded incident/
  );
});

test("review mode resolves the encrypted vault incident checkpoint identity", () => {
  const prompt = "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=checkpoint-1";
  const history = [{
    checkpointId: "checkpoint-1",
    speaker: "HODR",
    reply: { turn_type: "IMPROVEMENT_INCIDENT" }
  }];
  const result = buildBuilderModeInstructions("BALDR", prompt, history);
  assert.match(result, /affected Builder is HODR/);
  assert.match(result, /MUST NOT evaluate, approve, or certify its own correction/);
  assert.match(result, /Only an unaffected Baldr may issue VERIFIED/);
  assert.match(result, /Never issue ADOPTED/);
  assert.match(result, /github\\+commit/);
  assert.match(result, /github\\+actions/);
  assert.match(result, /Never invent a SHA, run ID, workflow name, branch, or result/);
});
