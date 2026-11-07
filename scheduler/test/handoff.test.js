import test from "node:test";
import assert from "node:assert/strict";
import {
  HANDOFF_STATES,
  applyHandoffEvent,
  assertReceiptMatches,
  canExecuteTask,
  createHandoff,
  isHandoffState
} from "../src/handoff.js";

function makeHandoff(overrides = {}) {
  return createHandoff({
    handoffId: "handoff-001",
    taskId: "daily-check",
    taskRevision: 3,
    generation: 7,
    sourceId: "scheduler-a",
    destinationId: "scheduler-b",
    payloadDigest: "sha256:abc123",
    authorizationRef: "approval-001",
    createdAt: "2026-11-07T12:00:00.000Z",
    ...overrides
  });
}

function receipt(record, status = "PREPARED", overrides = {}) {
  return {
    receiptId: status === "PREPARED" ? "receipt-prepared-001" : "receipt-committed-001",
    handoffId: record.handoffId,
    taskId: record.taskId,
    taskRevision: record.taskRevision,
    generation: record.generation,
    sourceId: record.sourceId,
    destinationId: record.destinationId,
    payloadDigest: record.payloadDigest,
    status,
    ...overrides
  };
}

function advanceTo(record, target) {
  const events = {
    PREPARED: "PREPARE",
    SENT: "SEND",
    ACCEPTED_UNCONFIRMED: "DESTINATION_PREPARED",
    SOURCE_FENCED: "SOURCE_FENCED",
    COMMIT_SENT: "SEND_COMMIT",
    TRANSFERRED: "DESTINATION_COMMITTED"
  };
  const order = ["REQUESTED", "PREPARED", "SENT", "ACCEPTED_UNCONFIRMED", "SOURCE_FENCED", "COMMIT_SENT", "TRANSFERRED"];
  let current = record;
  for (let i = 1; i < order.indexOf(target) + 1; i += 1) {
    const state = order[i];
    const type = events[state];
    const event = { type, generation: current.generation };
    if (state === "ACCEPTED_UNCONFIRMED") event.receipt = receipt(current, "PREPARED");
    if (state === "TRANSFERRED") event.receipt = receipt(current, "COMMITTED");
    current = applyHandoffEvent(current, event);
  }
  return current;
}

test("creates a source-owned handoff in REQUESTED state", () => {
  const record = makeHandoff();
  assert.equal(record.state, "REQUESTED");
  assert.equal(record.ownerId, "scheduler-a");
  assert.equal(record.generation, 7);
});

test("rejects missing identity, authorization, and digest fields", () => {
  for (const field of ["handoffId", "taskId", "sourceId", "destinationId", "payloadDigest", "authorizationRef"]) {
    const input = {
      handoffId: "h", taskId: "t", taskRevision: 1, generation: 1,
      sourceId: "a", destinationId: "b", payloadDigest: "d", authorizationRef: "auth"
    };
    input[field] = " ";
    assert.throws(() => createHandoff(input), /non-empty string/);
  }
});

test("rejects same source and destination and invalid counters", () => {
  assert.throws(() => makeHandoff({ destinationId: "scheduler-a" }), /different schedulers/);
  assert.throws(() => makeHandoff({ taskRevision: 0 }), /taskRevision/);
  assert.throws(() => makeHandoff({ generation: 0 }), /generation/);
});

test("exports a stable set of recognized states", () => {
  assert.ok(HANDOFF_STATES.includes("SOURCE_FENCED"));
  assert.equal(isHandoffState("TRANSFERRED"), true);
  assert.equal(isHandoffState("RUNNING"), false);
});

test("prepares and sends a handoff in order", () => {
  const requested = makeHandoff();
  const prepared = applyHandoffEvent(requested, { type: "PREPARE", generation: 7 });
  const sent = applyHandoffEvent(prepared, { type: "SEND", generation: 7 });
  assert.equal(prepared.state, "PREPARED");
  assert.equal(sent.state, "SENT");
  assert.equal(requested.state, "REQUESTED");
});

test("rejects sending before preparation", () => {
  assert.throws(() => applyHandoffEvent(makeHandoff(), { type: "SEND", generation: 7 }), /Invalid handoff transition/);
});

test("accepts a matching durable preparation receipt without transferring ownership", () => {
  const sent = advanceTo(makeHandoff(), "SENT");
  const preparedReceipt = receipt(sent, "PREPARED");
  const accepted = applyHandoffEvent(sent, {
    type: "DESTINATION_PREPARED", generation: 7, receipt: preparedReceipt
  });
  assert.equal(accepted.state, "ACCEPTED_UNCONFIRMED");
  assert.equal(accepted.preparedReceiptId, preparedReceipt.receiptId);
  assert.equal(accepted.ownerId, "scheduler-a");
});

test("rejects a receipt with any mismatched binding field", () => {
  const record = makeHandoff();
  for (const field of ["handoffId", "taskId", "taskRevision", "generation", "sourceId", "destinationId", "payloadDigest"]) {
    const invalid = receipt(record, "PREPARED", { [field]: field === "taskRevision" || field === "generation" ? 99 : "wrong" });
    assert.throws(() => assertReceiptMatches(record, invalid, "PREPARED"), /Receipt mismatch/);
  }
});

test("rejects a receipt with the wrong status or no receipt identity", () => {
  const record = makeHandoff();
  assert.throws(() => assertReceiptMatches(record, receipt(record, "COMMITTED"), "PREPARED"), /status/);
  assert.throws(() => assertReceiptMatches(record, receipt(record, "PREPARED", { receiptId: "" }), "PREPARED"), /receiptId/);
});

test("requires source fencing before commit can be sent", () => {
  const accepted = advanceTo(makeHandoff(), "ACCEPTED_UNCONFIRMED");
  assert.throws(() => applyHandoffEvent(accepted, { type: "SEND_COMMIT", generation: 7 }), /Invalid handoff transition/);
  const fenced = applyHandoffEvent(accepted, { type: "SOURCE_FENCED", generation: 7 });
  assert.equal(fenced.state, "SOURCE_FENCED");
  assert.equal(applyHandoffEvent(fenced, { type: "SEND_COMMIT", generation: 7 }).state, "COMMIT_SENT");
});

test("transfers ownership only after a matching committed receipt", () => {
  const commitSent = advanceTo(makeHandoff(), "COMMIT_SENT");
  const committedReceipt = receipt(commitSent, "COMMITTED");
  const transferred = applyHandoffEvent(commitSent, {
    type: "DESTINATION_COMMITTED", generation: 7, receipt: committedReceipt
  });
  assert.equal(transferred.state, "TRANSFERRED");
  assert.equal(transferred.ownerId, "scheduler-b");
  assert.equal(transferred.committedReceiptId, committedReceipt.receiptId);
  assert.equal(commitSent.ownerId, "scheduler-a");
});

test("rejects stale generations on ordinary protocol events", () => {
  assert.throws(() => applyHandoffEvent(makeHandoff(), { type: "PREPARE", generation: 6 }), /Stale or mismatched/);
  assert.throws(() => applyHandoffEvent(makeHandoff(), { type: "PREPARE", generation: 8 }), /Stale or mismatched/);
});

test("pause and cancel require a strictly newer generation", () => {
  const record = makeHandoff();
  assert.throws(() => applyHandoffEvent(record, { type: "PAUSE", generation: 7 }), /newer generation/);
  assert.throws(() => applyHandoffEvent(record, { type: "CANCEL", generation: 6 }), /newer generation/);
  assert.equal(applyHandoffEvent(record, { type: "PAUSE", generation: 8 }).state, "PAUSED");
  assert.equal(applyHandoffEvent(record, { type: "CANCEL", generation: 8 }).state, "CANCEL_PENDING");
});

test("a stale cancellation cannot overwrite a newer pause generation", () => {
  const paused = applyHandoffEvent(makeHandoff(), { type: "PAUSE", generation: 8 });
  assert.throws(() => applyHandoffEvent(paused, { type: "CANCEL", generation: 8 }), /newer generation/);
});

test("source restoration requires destination proof that commit did not occur", () => {
  const commitSent = advanceTo(makeHandoff(), "COMMIT_SENT");
  const uncertain = applyHandoffEvent(commitSent, { type: "REQUIRE_RECOVERY", generation: 7 });
  assert.throws(() => applyHandoffEvent(uncertain, {
    type: "RESOLVE_TO_SOURCE", generation: 7
  }), /destination proof/);
  const restored = applyHandoffEvent(uncertain, {
    type: "RESOLVE_TO_SOURCE",
    generation: 7,
    receipt: receipt(uncertain, "CANCELLED")
  });
  assert.equal(uncertain.state, "RECOVERY_REQUIRED");
  assert.equal(restored.state, "REJECTED");
  assert.equal(restored.ownerId, "scheduler-a");
  assert.equal(commitSent.state, "COMMIT_SENT");
});

test("recovery can resolve to destination only with a matching committed receipt", () => {
  const uncertain = applyHandoffEvent(advanceTo(makeHandoff(), "COMMIT_SENT"), {
    type: "REQUIRE_RECOVERY", generation: 7
  });
  assert.throws(() => applyHandoffEvent(uncertain, {
    type: "RESOLVE_TO_DESTINATION", generation: 7
  }), /committed receipt/);
  const resolved = applyHandoffEvent(uncertain, {
    type: "RESOLVE_TO_DESTINATION",
    generation: 7,
    receipt: receipt(uncertain, "COMMITTED")
  });
  assert.equal(resolved.state, "TRANSFERRED");
  assert.equal(resolved.ownerId, "scheduler-b");
});

test("source may execute before fencing, but neither side executes across a fence", () => {
  const common = { generation: 7, currentGeneration: 7, localSchedulerId: "scheduler-a", ownerId: "scheduler-a" };
  assert.equal(canExecuteTask({ ...common, handoffState: "REQUESTED" }), true);
  assert.equal(canExecuteTask({ ...common, handoffState: "SENT" }), true);
  assert.equal(canExecuteTask({ ...common, handoffState: "ACCEPTED_UNCONFIRMED" }), true);
  assert.equal(canExecuteTask({ ...common, handoffState: "SOURCE_FENCED" }), false);
  assert.equal(canExecuteTask({ ...common, handoffState: "COMMIT_SENT" }), false);
  assert.equal(canExecuteTask({ ...common, handoffState: "PAUSED" }), false);
  assert.equal(canExecuteTask({ ...common, handoffState: "unknown-state" }), false);
});

test("execution requires the current owner and generation", () => {
  const common = { generation: 7, currentGeneration: 7, handoffState: null };
  assert.equal(canExecuteTask({ ...common, localSchedulerId: "scheduler-b", ownerId: "scheduler-a" }), false);
  assert.equal(canExecuteTask({ ...common, localSchedulerId: "scheduler-a", ownerId: "scheduler-a", currentGeneration: 8 }), false);
  assert.equal(canExecuteTask({ ...common, localSchedulerId: "scheduler-b", ownerId: "scheduler-b", handoffState: "TRANSFERRED" }), true);
});

test("rejects unknown events and transitions from terminal states", () => {
  const rejected = applyHandoffEvent(makeHandoff(), { type: "REJECT", generation: 7 });
  assert.equal(rejected.state, "REJECTED");
  assert.throws(() => applyHandoffEvent(rejected, { type: "PREPARE", generation: 7 }), /Invalid handoff transition/);
  assert.throws(() => applyHandoffEvent(makeHandoff(), { type: "MAGIC", generation: 7 }), /Unknown handoff event/);
});

test("rejects a committed receipt with an old generation", () => {
  const commitSent = advanceTo(makeHandoff(), "COMMIT_SENT");
  assert.throws(() => applyHandoffEvent(commitSent, {
    type: "DESTINATION_COMMITTED",
    generation: 7,
    receipt: receipt(commitSent, "COMMITTED", { generation: 6 })
  }), /Receipt mismatch: generation/);
});
