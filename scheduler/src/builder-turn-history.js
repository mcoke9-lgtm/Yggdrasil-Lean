const DIRECTED_TYPES = new Set([
  "QUESTION", "CHALLENGE", "RESPONSE", "HANDOFF", "ACKNOWLEDGEMENT",
  "RECONCILIATION", "INTERRUPTION", "IMPROVEMENT_INCIDENT",
  "CORRECTION_PLAN", "CORRECTION_UPDATE"
]);
const REFERENCE_TYPES = new Set([
  "CHALLENGE", "RESPONSE", "HANDOFF", "ACKNOWLEDGEMENT",
  "RECONCILIATION", "INTERRUPTION", "IMPROVEMENT_INCIDENT",
  "CORRECTION_PLAN", "CORRECTION_UPDATE"
]);
const CORRECTION_STAGES = ["IMPLEMENTED", "TESTED", "VERIFIED"];

function nonEmpty(value, label, max = 4096) {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new TypeError(label + " must be a non-empty string no longer than " + max + " characters");
  }
  return value.trim();
}

function parseObject(body, label) {
  let value;
  try { value = JSON.parse(body); }
  catch { throw new Error(label + " body must be a JSON object"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(label + " body must be a JSON object");
  }
  return value;
}

function turnId(turn) {
  const id = turn?.checkpointId ?? turn?.turnId;
  return typeof id === "string" && id.trim() ? id.trim() : null;
}

function turnType(turn) {
  return turn?.reply?.turn_type ?? turn?.type ?? null;
}

function turnReplyTo(turn) {
  return turn?.reply?.reply_to_turn_id ?? turn?.reply_to_turn_id ?? null;
}

function turnBody(turn) {
  return typeof turn?.reply?.body === "string" ? turn.reply.body : turn?.body;
}

/**
 * Validate and retain the vault's original records. Missing timestamps on legacy
 * records remain missing; this function never manufactures historical time.
 */
export function validateBuilderHistory(history, runId, threadId) {
  if (!Array.isArray(history)) throw new TypeError("Run history must be an array");
  const ids = new Map();
  for (let index = 0; index < history.length; index += 1) {
    const turn = history[index];
    if (!turn || typeof turn !== "object" || Array.isArray(turn)) {
      throw new Error("Run history contains a malformed turn at index " + index);
    }
    if (turn.runId !== runId || turn.threadId !== threadId) {
      throw new Error("Task history contains data outside this scheduled run's dedicated thread");
    }
    nonEmpty(turn.speaker, "Run history speaker", 32);
    nonEmpty(turn.body, "Run history body", 16000);
    const id = turnId(turn);
    if (id) {
      if (ids.has(id)) throw new Error("Run history contains duplicate turn identity " + id);
      ids.set(id, index);
    }
    if (turn.completedAt != null) {
      const timestamp = nonEmpty(turn.completedAt, "Run history completedAt", 64);
      const parsed = new Date(timestamp);
      if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== timestamp) {
        throw new Error("Run history completedAt must be a valid canonical ISO-8601 timestamp");
      }
    }
    const replyTo = turnReplyTo(turn);
    if (replyTo != null) {
      const targetIndex = ids.get(replyTo);
      if (targetIndex == null || targetIndex >= index) {
        throw new Error("Run history reply reference must point to an earlier turn in this run");
      }
    }
  }
  validateCorrectionHistory(history, ids);
  return history;
}

function validateCorrectionHistory(history, ids) {
  const plans = new Map();
  const latestByCorrection = new Map();
  for (let index = 0; index < history.length; index += 1) {
    const turn = history[index];
    const type = turnType(turn);
    if (type === "IMPROVEMENT_INCIDENT") continue;
    if (type === "CORRECTION_PLAN") {
      const item = parseObject(turnBody(turn), "Correction plan");
      const correctionId = nonEmpty(item.correction_id, "Correction plan correction_id", 256);
      const incidentId = nonEmpty(item.incident_turn_id, "Correction plan incident_turn_id", 256);
      if (plans.has(correctionId)) throw new Error("Correction ID is already planned in this run: " + correctionId);
      const incidentIndex = ids.get(incidentId);
      if (incidentIndex == null || incidentIndex >= index ||
          turnType(history[incidentIndex]) !== "IMPROVEMENT_INCIDENT") {
        throw new Error("Correction plan must reference an earlier recorded incident in this run");
      }
      if (turnReplyTo(turn) !== incidentId) {
        throw new Error("Correction plan must reply directly to its referenced incident");
      }
      if (item.status !== "PROPOSED" || turn.reply?.evidence_status !== "PROPOSED") {
        throw new Error("Correction plan status and evidence_status must remain PROPOSED");
      }
      plans.set(correctionId, { incidentId, planIndex: index, stageIndex: -1, latestId: turnId(turn) });
      latestByCorrection.set(correctionId, turnId(turn));
      continue;
    }
    if (type !== "CORRECTION_UPDATE") continue;
    const item = parseObject(turnBody(turn), "Correction update");
    const correctionId = nonEmpty(item.correction_id, "Correction update correction_id", 256);
    const incidentId = nonEmpty(item.incident_turn_id, "Correction update incident_turn_id", 256);
    const plan = plans.get(correctionId);
    if (!plan) throw new Error("Correction update has no earlier correction plan in this run");
    if (incidentId !== plan.incidentId) throw new Error("Correction update incident does not match its correction plan");
    if (index <= plan.planIndex) throw new Error("Correction update must follow its correction plan");
    if (turnReplyTo(turn) !== plan.latestId) {
      throw new Error("Correction update must reply to the immediately preceding lifecycle record");
    }
    const stageIndex = CORRECTION_STAGES.indexOf(item.stage);
    if (stageIndex < 0 || stageIndex !== plan.stageIndex + 1) {
      throw new Error("Correction stages must advance exactly IMPLEMENTED, TESTED, VERIFIED");
    }
    const speaker = turn.speaker;
    const incident = history[ids.get(plan.incidentId)];
    if (turn.reply?.evidence_status !== item.stage) {
      throw new Error("Correction update evidence_status must match its lifecycle stage");
    }
    if (item.stage === "VERIFIED" && (speaker !== "BALDR" || speaker === incident.speaker)) {
      throw new Error("Only unaffected Baldr may issue VERIFIED");
    }
    if (item.stage === "IMPLEMENTED" && !nonEmpty(item.artifact_reference, "Correction implementation artifact_reference", 2048)) {
      throw new Error("IMPLEMENTED requires an artifact reference");
    }
    if (item.stage === "TESTED" && !nonEmpty(item.artifact_reference, "Correction test artifact_reference", 2048)) {
      throw new Error("TESTED requires a test run or result reference");
    }
    plan.stageIndex = stageIndex;
    plan.latestId = turnId(turn);
    latestByCorrection.set(correctionId, plan.latestId);
  }
}

/** Validate a new model reply against already persisted turns before it is appended. */
export function validateBuilderReplyAgainstHistory(reply, history, speaker, promptTurnId) {
  if (!reply || typeof reply !== "object") throw new TypeError("Builder reply is required");
  const type = reply.turn_type;
  const replyTo = reply.reply_to_turn_id;
  const byId = new Map();
  if (promptTurnId != null) {
    byId.set(nonEmpty(promptTurnId, "Task prompt turn identity", 256), {
      turn: { speaker: "USER", type: "TASK_PROMPT" }, index: -1
    });
  }
  for (let index = 0; index < history.length; index += 1) {
    const id = turnId(history[index]);
    if (id) byId.set(id, { turn: history[index], index });
  }
  if (DIRECTED_TYPES.has(type) && !reply.recipient) {
    throw new Error(type + " must name a recipient");
  }
  if (REFERENCE_TYPES.has(type)) {
    const target = byId.get(replyTo);
    if (!target) throw new Error(type + " must reference an earlier turn in this run's thread");
    if (target.index >= history.length) throw new Error(type + " reply reference is not earlier than the new turn");
  }
  if (type === "CORRECTION_PLAN") {
    const item = parseObject(reply.body, "Correction plan");
    if (item.incident_turn_id !== replyTo) {
      throw new Error("Correction plan must reply directly to its referenced incident");
    }
    if (turnType(byId.get(item.incident_turn_id)?.turn) !== "IMPROVEMENT_INCIDENT") {
      throw new Error("Correction plan must reference a recorded improvement incident");
    }
  }
  if (type === "CORRECTION_UPDATE") {
    const item = parseObject(reply.body, "Correction update");
    const prior = history.filter(turn => {
      if (turnType(turn) === "CORRECTION_PLAN" || turnType(turn) === "CORRECTION_UPDATE") {
        const parsed = parseObject(turnBody(turn), "Prior correction record");
        return parsed.correction_id === item.correction_id;
      }
      return false;
    });
    if (!prior.length) throw new Error("Correction update has no prior correction plan");
    const latest = prior[prior.length - 1];
    if (replyTo !== turnId(latest)) {
      throw new Error("Correction update must reply to the immediately preceding lifecycle record");
    }
    if (item.incident_turn_id !== parseObject(turnBody(prior[0]), "Correction plan").incident_turn_id) {
      throw new Error("Correction update incident does not match its correction plan");
    }
    const completedStages = prior.filter(turn => turnType(turn) === "CORRECTION_UPDATE")
      .map(turn => parseObject(turnBody(turn), "Prior correction update").stage);
    const nextStage = CORRECTION_STAGES[completedStages.length];
    if (item.stage !== nextStage) throw new Error("Correction stages must advance exactly IMPLEMENTED, TESTED, VERIFIED");
    const incident = byId.get(item.incident_turn_id)?.turn;
    if (item.stage === "VERIFIED" && (speaker !== "BALDR" || speaker === incident?.speaker)) {
      throw new Error("Only unaffected Baldr may issue VERIFIED");
    }
    if (reply.evidence_status !== item.stage) {
      throw new Error("Correction update evidence_status must match its lifecycle stage");
    }
    if (item.stage === "IMPLEMENTED" || item.stage === "TESTED") {
      nonEmpty(item.artifact_reference, "Correction update artifact_reference", 2048);
    }
  }
  return reply;
}
