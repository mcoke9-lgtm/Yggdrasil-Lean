import test from "node:test";
import assert from "node:assert/strict";
import {
  validateBuilderHistory,
  validateBuilderReplyAgainstHistory
} from "../src/builder-turn-history.js";

const runId = "run-1";
const threadId = "nine-builders:run-1";

function turn(checkpointId, speaker, type, replyTo = null, body = "record", evidenceStatus = "OBSERVED") {
  return {
    runId, threadId, checkpointId, speaker, body,
    reply: {
      body, turn_type: type, reply_to_turn_id: replyTo,
      evidence_status: evidenceStatus
    }
  };
}

function correctionHistory() {
  const incidentBody = JSON.stringify({
    incident_summary: "Wrong result", observed_behavior: "A", expected_behavior: "B",
    evidence: "test output", unknowns: "none", suspected_causes: "mapping",
    proposed_next_step: "review", status: "OPEN_REVIEW"
  });
  const planBody = JSON.stringify({
    correction_id: "corr-1", incident_turn_id: "incident-1", objective: "Fix mapping",
    acceptance_criteria: "test passes", steps: "change code", owner: "HODR",
    rollback_condition: "regression", status: "PROPOSED"
  });
  const update = (stage, replyTo, id, speaker) => turn(
    id, speaker, "CORRECTION_UPDATE", replyTo,
    JSON.stringify({
      correction_id: "corr-1", incident_turn_id: "incident-1", stage,
      evidence: "recorded evidence", artifact_reference: "run-or-commit",
      acceptance_criteria_result: "checked", unresolved: "none"
    }), stage
  );
  return [
    turn("incident-1", "HODR", "IMPROVEMENT_INCIDENT", null, incidentBody, "OBSERVED"),
    turn("plan-1", "TYR", "CORRECTION_PLAN", "incident-1", planBody, "PROPOSED"),
    update("IMPLEMENTED", "plan-1", "implemented-1", "HODR"),
    update("TESTED", "implemented-1", "tested-1", "HODR")
  ];
}

test("preserves legacy records with missing timestamps without inventing dates", () => {
  const history = [turn("turn-1", "ODIN", "CONTRIBUTION")];
  assert.equal(validateBuilderHistory(history, runId, threadId), history);
  assert.equal(Object.hasOwn(history[0], "completedAt"), false);
});

test("rejects history from another run or thread", () => {
  const foreign = { ...turn("turn-1", "ODIN", "CONTRIBUTION"), runId: "run-2" };
  assert.throws(() => validateBuilderHistory([foreign], runId, threadId), /outside this scheduled run/);
});

test("rejects duplicate stable turn identities", () => {
  const history = [
    turn("same-id", "ODIN", "CONTRIBUTION"),
    turn("same-id", "HODR", "CONTRIBUTION")
  ];
  assert.throws(() => validateBuilderHistory(history, runId, threadId), /duplicate turn identity/);
});

test("rejects forward and unresolved history reply references", () => {
  const history = [
    turn("first", "ODIN", "RESPONSE", "later"),
    turn("later", "HODR", "CONTRIBUTION")
  ];
  assert.throws(() => validateBuilderHistory(history, runId, threadId), /earlier turn/);
});

test("rejects malformed source timestamps but accepts absent legacy timestamps", () => {
  const bad = { ...turn("turn-1", "ODIN", "CONTRIBUTION"), completedAt: "yesterday" };
  assert.throws(() => validateBuilderHistory([bad], runId, threadId), /canonical ISO-8601/);
  const good = { ...turn("turn-2", "ODIN", "CONTRIBUTION"), completedAt: "2026-11-05T12:00:00.000Z" };
  assert.doesNotThrow(() => validateBuilderHistory([good], runId, threadId));
});

test("new directed replies must reference an existing same-run turn", () => {
  const history = [turn("turn-1", "ODIN", "CONTRIBUTION")];
  assert.doesNotThrow(() => validateBuilderReplyAgainstHistory({
    turn_type: "RESPONSE", recipient: "ODIN", reply_to_turn_id: "turn-1"
  }, history, "HODR"));
  assert.throws(() => validateBuilderReplyAgainstHistory({
    turn_type: "RESPONSE", recipient: "ODIN", reply_to_turn_id: "missing"
  }, history, "HODR"), /earlier turn in this run/);
});

test("correction plan must point directly to a recorded incident", () => {
  const history = [turn("incident-1", "HODR", "IMPROVEMENT_INCIDENT")];
  const body = JSON.stringify({
    correction_id: "corr-1", incident_turn_id: "incident-1", objective: "fix",
    acceptance_criteria: "pass", steps: "change", owner: "HODR",
    rollback_condition: "regression", status: "PROPOSED"
  });
  assert.doesNotThrow(() => validateBuilderReplyAgainstHistory({
    turn_type: "CORRECTION_PLAN", recipient: "USER",
    reply_to_turn_id: "incident-1", body
  }, history, "TYR"));
  assert.throws(() => validateBuilderReplyAgainstHistory({
    turn_type: "CORRECTION_PLAN", recipient: "USER",
    reply_to_turn_id: "missing", body
  }, history, "TYR"), /earlier turn in this run/);
});

test("correction history enforces incident linkage and ordered lifecycle", () => {
  const history = correctionHistory();
  assert.doesNotThrow(() => validateBuilderHistory(history, runId, threadId));
  const outOfOrder = [...history];
  outOfOrder[3] = {
    ...outOfOrder[3],
    reply: { ...outOfOrder[3].reply, body: JSON.stringify({
      correction_id: "corr-1", incident_turn_id: "incident-1", stage: "VERIFIED",
      evidence: "evidence", artifact_reference: "artifact", acceptance_criteria_result: "pass",
      unresolved: "none"
    }), evidence_status: "VERIFIED" },
    body: JSON.stringify({
      correction_id: "corr-1", incident_turn_id: "incident-1", stage: "VERIFIED",
      evidence: "evidence", artifact_reference: "artifact", acceptance_criteria_result: "pass",
      unresolved: "none"
    })
  };
  assert.throws(() => validateBuilderHistory(outOfOrder, runId, threadId), /stages must advance exactly/);
});

test("only unaffected Baldr can issue VERIFIED after TESTED", () => {
  const history = correctionHistory();
  const verifiedBody = JSON.stringify({
    correction_id: "corr-1", incident_turn_id: "incident-1", stage: "VERIFIED",
    evidence: "independent check", artifact_reference: "verification-run",
    acceptance_criteria_result: "all pass", unresolved: "none"
  });
  assert.throws(() => validateBuilderReplyAgainstHistory({
    turn_type: "CORRECTION_UPDATE", recipient: "USER",
    reply_to_turn_id: "tested-1", evidence_status: "VERIFIED", body: verifiedBody
  }, history, "HODR"), /Only unaffected Baldr/);
  assert.doesNotThrow(() => validateBuilderReplyAgainstHistory({
    turn_type: "CORRECTION_UPDATE", recipient: "USER",
    reply_to_turn_id: "tested-1", evidence_status: "VERIFIED", body: verifiedBody
  }, history, "BALDR"));
});
