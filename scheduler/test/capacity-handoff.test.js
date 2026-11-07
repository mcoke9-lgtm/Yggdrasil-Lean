import test from "node:test";
import assert from "node:assert/strict";
import { createCapacityHandoffRequest } from "../src/capacity-handoff.js";

const taskDefinition = {
  id: "daily-review",
  name: "Daily review",
  handlerType: "review.run",
  payload: { scope: "repository" },
  schedule: {
    kind: "calendar",
    frequency: "weekly",
    daysOfWeek: [1],
    hour: 9,
    minute: 30,
    timezone: "America/New_York"
  }
};

test("converts an explicit capacity rejection without altering the task definition", () => {
  const original = structuredClone(taskDefinition);
  const result = createCapacityHandoffRequest({
    rejection: { code: "CAPACITY_REACHED", observedAt: "2026-11-06T11:59:00.000Z", capacity: 3, occupied: 3 },
    handoffId: "handoff-capacity-001",
    sourceScheduler: "host-scheduler",
    taskDefinition,
    taskDefinitionVersion: "7",
    authorization: { granted: true, authority: "user-request-123" }
  });
  assert.equal(result.eligible, true);
  assert.deepEqual(result.request.taskDefinition, original);
  assert.deepEqual(taskDefinition, original);
  assert.equal(result.request.sourceRejectionEvidence.code, "CAPACITY_REACHED");
  assert.equal(result.request.taskDefinition.schedule.timezone, "America/New_York");
});

test("does not treat permission or service errors as capacity rejections", () => {
  const result = createCapacityHandoffRequest({
    rejection: { code: "PERMISSION_DENIED" },
    handoffId: "handoff-capacity-002",
    sourceScheduler: "host-scheduler",
    taskDefinition,
    authorization: { granted: true }
  });
  assert.deepEqual(result, { eligible: false, reason: "rejection_is_not_capacity_related" });
});

test("requires stable identity, full task definition and explicit authorization", () => {
  const base = {
    rejection: { code: "CAPACITY_REACHED" },
    handoffId: "handoff-capacity-003",
    sourceScheduler: "host-scheduler",
    taskDefinition,
    authorization: { granted: true }
  };
  assert.throws(() => createCapacityHandoffRequest({ ...base, handoffId: "" }), /stable handoffId/);
  assert.throws(() => createCapacityHandoffRequest({ ...base, taskDefinition: null }), /complete original taskDefinition/);
  assert.throws(() => createCapacityHandoffRequest({ ...base, authorization: null }), /Explicit authorization/);
});
