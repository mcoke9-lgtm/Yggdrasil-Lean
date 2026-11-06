const DIRECTED_TYPES = new Set([
  "QUESTION", "CHALLENGE", "RESPONSE", "HANDOFF", "ACKNOWLEDGEMENT",
  "INTERRUPTION", "IMPROVEMENT_INCIDENT", "CORRECTION_PLAN"
]);
const SUPPORTED_TURN_TYPES = new Set([
  "CONTRIBUTION", "QUESTION", "CHALLENGE", "RESPONSE", "HANDOFF",
  "ACKNOWLEDGEMENT", "RECONCILIATION", "INTERRUPTION",
  "IMPROVEMENT_INCIDENT", "CORRECTION_PLAN", "CORRECTION_UPDATE", "DECISION"
]);
const SUPPORTED_EVIDENCE_STATUSES = new Set([
  "PROPOSED", "OBSERVED", "IMPLEMENTED", "TESTED", "VERIFIED",
  "ADOPTED", "USER_CONFIRMED", "DISPUTED", "UNKNOWN"
]);
const REFERENCE_TYPES = new Set([
  "CHALLENGE", "RESPONSE", "HANDOFF", "ACKNOWLEDGEMENT",
  "RECONCILIATION", "INTERRUPTION", "IMPROVEMENT_INCIDENT",
  "CORRECTION_PLAN", "CORRECTION_UPDATE"
]);
const REPLY_RECIPIENT_EXEMPTIONS = new Set([
  "HANDOFF", "INTERRUPTION", "IMPROVEMENT_INCIDENT",
  "CORRECTION_PLAN", "CORRECTION_UPDATE"
]);
const ANSWER_TYPES = new Set(["RESPONSE", "ACKNOWLEDGEMENT"]);
const DIRECTED_PRIOR_TYPES = new Set(["QUESTION", "CHALLENGE", "HANDOFF"]);
const CORRECTION_STAGES = ["IMPLEMENTED", "TESTED", "VERIFIED"];
const CURRENT_PROTOCOL_VERSION = 4;
const LEGACY_PROTOCOL_VERSION = 1;
const MAX_BODY_LENGTH = 8000;

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
  const id = turn?.turnId ?? turn?.checkpointId;
  return typeof id === "string" && id.trim() ? id.trim() : null;
}

function turnType(turn) {
  return turn?.reply?.turn_type ?? turn?.type ?? null;
}

function turnEvidenceStatus(turn) {
  return turn?.reply?.evidence_status ?? turn?.evidence_status ?? null;
}

function turnRecipient(turn) {
  return turn?.reply?.recipient ?? turn?.recipient ?? null;
}

function turnReplyTo(turn) {
  return turn?.reply?.reply_to_turn_id ?? turn?.reply_to_turn_id ?? null;
}

function turnBody(turn) {
  return typeof turn?.reply?.body === "string" ? turn.reply.body : turn?.body;
}

function parseEvidenceReference(value, expectedKind, stage) {
  const raw = nonEmpty(value, "Correction " + stage + " artifact_reference", 2048);
  let url;
  try { url = new URL(raw); }
  catch { throw new Error(stage + " requires a typed GitHub evidence reference"); }
  if (url.hostname !== "github.com") {
    throw new Error(stage + " evidence reference must identify github.com");
  }
  const parts = url.pathname.split("/");
  if (url.protocol === "github+commit:" && expectedKind === "COMMIT") {
    if (parts.length !== 4 || !/^[0-9a-fA-F]{40}$/.test(parts[3]) ||
        !url.searchParams.get("branch")?.trim()) {
      throw new Error("IMPLEMENTED requires a typed full-SHA GitHub commit reference with branch");
    }
    return { kind: "COMMIT" };
  }
  if (url.protocol === "github+actions:" && expectedKind === "ACTIONS_RUN") {
    if (parts.length !== 5 || parts[3] !== "runs" || !/^[1-9][0-9]*$/.test(parts[4]) ||
        !url.searchParams.get("workflow")?.trim() ||
        !/^[0-9a-fA-F]{40}$/.test(url.searchParams.get("sha") || "")) {
      throw new Error(stage + " requires a typed GitHub Actions run reference with workflow and tested SHA");
    }
    return { kind: "ACTIONS_RUN" };
  }
  throw new Error(stage + " requires a typed " + expectedKind + " GitHub evidence reference");
}

function validateReplyShape(type, speaker, recipient, replyTo, evidenceStatus) {
  if (!SUPPORTED_TURN_TYPES.has(type)) throw new Error("Run history contains an unsupported turn type");
  if (!SUPPORTED_EVIDENCE_STATUSES.has(evidenceStatus)) throw new Error("Run history contains an unsupported evidence status");
  if (type === "DECISION" && speaker !== "USER") throw new Error("Builders cannot create DECISION turns");
  const userAdoption = type === "CORRECTION_UPDATE" && speaker === "USER" && evidenceStatus === "ADOPTED";
  if (evidenceStatus === "ADOPTED" && !userAdoption) throw new Error("Only USER may record ADOPTED");
  if ((DIRECTED_TYPES.has(type) || (type === "CORRECTION_UPDATE" && speaker !== "USER")) && recipient == null) {
    throw new Error(type + " must name a recipient");
  }
  if (userAdoption && recipient != null) throw new Error("User correction adoption must not have a recipient");
  if (REFERENCE_TYPES.has(type) && !replyTo) {
    throw new Error(type + " must reference an earlier turn");
  }
  if (recipient != null && recipient === speaker) {
    throw new Error("A participant cannot address a turn to itself");
  }
  if ((type === "INTERRUPTION" || type === "IMPROVEMENT_INCIDENT" ||
       type === "CORRECTION_PLAN" || (type === "CORRECTION_UPDATE" && !userAdoption)) &&
      recipient !== "USER") {
    throw new Error(type + " must be addressed to USER");
  }
}

/**
 * Validate and retain the vault's original records. Missing timestamps on legacy
 * records remain missing; this function never manufactures historical time.
 */
export function validateBuilderHistory(history, runId, threadId, promptTurnId) {
  if (!Array.isArray(history)) throw new TypeError("Run history must be an array");
  const ids = new Map();
  if (promptTurnId != null) {
    ids.set(nonEmpty(promptTurnId, "Task prompt turn identity", 256), -1);
  }
  let previousTimestamp = null;
  for (let index = 0; index < history.length; index += 1) {
    const turn = history[index];
    if (!turn || typeof turn !== "object" || Array.isArray(turn)) {
      throw new Error("Run history contains a malformed turn at index " + index);
    }
    if (turn.runId !== runId || turn.threadId !== threadId) {
      throw new Error("Task history contains data outside this scheduled run's dedicated thread");
    }
    const speaker = nonEmpty(turn.speaker, "Run history speaker", 32);
    if (speaker !== "USER" && !BUILDER_ROLES.includes(speaker)) {
      throw new Error("Run history contains an unknown speaker");
    }
    const body = nonEmpty(turn.body, "Run history body", MAX_BODY_LENGTH);
    const id = turnId(turn);
    if (!id) throw new Error("Run history turn is missing its stable turnId or checkpointId");
    if (ids.has(id)) throw new Error("Run history contains duplicate turn identity " + id);
    ids.set(id, index);

    const protocolVersion = turn.protocolVersion == null
      ? LEGACY_PROTOCOL_VERSION : turn.protocolVersion;
    if (!Number.isInteger(protocolVersion) || protocolVersion < LEGACY_PROTOCOL_VERSION ||
        protocolVersion > CURRENT_PROTOCOL_VERSION) {
      throw new Error("Run history contains an unsupported conversation protocol version");
    }
    let timestamp = turn.timestampEpochMillis;
    if (turn.completedAt != null) {
      const completedAt = nonEmpty(turn.completedAt, "Run history completedAt", 64);
      const parsed = new Date(completedAt);
      if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== completedAt) {
        throw new Error("Run history completedAt must be a valid canonical ISO-8601 timestamp");
      }
      if (timestamp != null && timestamp !== parsed.getTime()) {
        throw new Error("Run history timestampEpochMillis does not match completedAt provenance");
      }
      timestamp = parsed.getTime();
    }
    if (timestamp == null) {
      if (protocolVersion >= CURRENT_PROTOCOL_VERSION) {
        throw new Error("Current protocol turns require a source timestamp");
      }
    } else {
      if (!Number.isSafeInteger(timestamp) || timestamp <= 0) {
        throw new Error("Run history timestampEpochMillis must be a positive safe integer");
      }
      if (previousTimestamp != null && timestamp <= previousTimestamp) {
        throw new Error("Run history timestamps must increase strictly in thread order");
      }
      previousTimestamp = timestamp;
    }

    if (turn.turnId != null && turn.checkpointId != null && turn.turnId !== turn.checkpointId) {
      throw new Error("Run history turnId and checkpointId must identify the same turn");
    }
    const type = turnType(turn);
    const evidenceStatus = turnEvidenceStatus(turn);
    const recipient = turnRecipient(turn);
    const replyTo = turnReplyTo(turn);
    if (turn.reply != null) {
      if (!turn.reply || typeof turn.reply !== "object" || Array.isArray(turn.reply)) {
        throw new Error("Run history reply envelope is malformed");
      }
      if (type != null) validateReplyShape(type, speaker, recipient, replyTo, evidenceStatus);
    }
    if (recipient != null &&
        recipient !== "USER" && !BUILDER_ROLES.includes(recipient)) {
      throw new Error("Run history contains an unknown recipient");
    }
    if (replyTo != null) {
      const targetIndex = ids.get(replyTo);
      if (targetIndex == null || targetIndex >= index) {
        throw new Error("Run history reply reference must point to an earlier turn in this run");
      }
      const target = replyTo === promptTurnId
        ? { speaker: "USER", type: "CONTRIBUTION", recipient: null }
        : history[targetIndex];
      validateReferenceRelationship(type, speaker, recipient, target);
    }
    // Keep this local read explicit: the body is bounded even for legacy records.
    if (body.length > MAX_BODY_LENGTH) throw new RangeError("Run history body exceeds the Android protocol limit");
  }
  validateCorrectionHistory(history, ids);
  return history;
}

function validateReferenceRelationship(type, speaker, recipient, target) {
  if (!target) throw new Error("Run history reply reference has no source turn");
  const targetSpeaker = target.speaker;
  const targetRecipient = turnRecipient(target);
  const targetType = turnType(target);
  if (!REPLY_RECIPIENT_EXEMPTIONS.has(type) && recipient != null &&
      recipient !== targetSpeaker) {
    throw new Error("Reply recipient must match the speaker of the referenced turn");
  }
  if (ANSWER_TYPES.has(type) && DIRECTED_PRIOR_TYPES.has(targetType) &&
      speaker !== targetRecipient) {
    throw new Error("Only the participant addressed by the referenced turn may answer or acknowledge it");
  }
}

function validateCorrectionHistory(history, ids) {
  const plans = new Map();
  const adopted = new Set();
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
      if (incidentIndex == null || incidentIndex < 0 || incidentIndex >= index ||
          turnType(history[incidentIndex]) !== "IMPROVEMENT_INCIDENT") {
        throw new Error("Correction plan must reference an earlier recorded incident in this run");
      }
      if (turnReplyTo(turn) !== incidentId) {
        throw new Error("Correction plan must reply directly to its referenced incident");
      }
      for (const field of ["objective", "acceptance_criteria", "steps", "owner", "rollback_condition"]) {
        nonEmpty(item[field], "Correction plan " + field, 4096);
      }
      if (item.status !== "PROPOSED" || turnEvidenceStatus(turn) !== "PROPOSED" ||
          turnRecipient(turn) !== "USER" || turn.speaker === "USER") {
        throw new Error("Correction plan must be a Builder-authored USER-directed PROPOSED record");
      }
      plans.set(correctionId, {
        incidentId, planIndex: index, stageIndex: -1,
        latestId: turnId(turn), latestStage: "PROPOSED"
      });
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
    if (item.stage === "ADOPTED") {
      if (turn.speaker !== "USER" || turnRecipient(turn) != null ||
          turnEvidenceStatus(turn) !== "ADOPTED" || plan.latestStage !== "VERIFIED") {
        throw new Error("Only USER may adopt the latest independently VERIFIED correction");
      }
      if (!nonEmpty(item.artifact_reference, "User adoption artifact_reference", 2048).startsWith("user-adoption:")) {
        throw new Error("User adoption requires an explicit user-action artifact reference");
      }
      nonEmpty(item.evidence, "User adoption evidence", 4096);
      nonEmpty(item.acceptance_criteria_result, "User adoption acceptance_criteria_result", 4096);
      nonEmpty(item.unresolved, "User adoption unresolved", 4096);
      if (plan.latestId == null || history[plan.stageIndex < 0 ? plan.planIndex :
          ids.get(plan.latestId)]?.speaker !== "BALDR") {
        throw new Error("User adoption must follow Baldr's independent verification");
      }
      adopted.add(correctionId);
      plan.latestId = turnId(turn);
      plan.latestStage = "ADOPTED";
      continue;
    }
    if (adopted.has(correctionId)) throw new Error("An adopted correction cannot receive further Builder updates");
    const stageIndex = CORRECTION_STAGES.indexOf(item.stage);
    if (stageIndex < 0 || stageIndex !== plan.stageIndex + 1) {
      throw new Error("Correction stages must advance exactly IMPLEMENTED, TESTED, VERIFIED");
    }
    if (turn.speaker === "USER" || turnRecipient(turn) !== "USER") {
      throw new Error("Builder correction updates must be authored by a Builder and addressed to USER");
    }
    if (turnEvidenceStatus(turn) !== item.stage) {
      throw new Error("Correction update evidence_status must match its lifecycle stage");
    }
    const incident = history[ids.get(plan.incidentId)];
    if (item.stage === "VERIFIED" && (turn.speaker !== "BALDR" || turn.speaker === incident.speaker)) {
      throw new Error("Only unaffected Baldr may issue VERIFIED");
    }
    if (item.stage === "IMPLEMENTED") {
      parseEvidenceReference(item.artifact_reference, "COMMIT", item.stage);
    }
    if (item.stage === "TESTED" || item.stage === "VERIFIED") {
      parseEvidenceReference(item.artifact_reference, "ACTIONS_RUN", item.stage);
    }
    const evidence = nonEmpty(item.evidence, "Correction update evidence", 4096);
    const criteria = nonEmpty(item.acceptance_criteria_result, "Correction update acceptance_criteria_result", 4096);
    nonEmpty(item.unresolved, "Correction update unresolved", 4096);
    if (evidence.length < 8 || criteria.length < 8) {
      throw new Error("Correction stage requires substantive evidence and acceptance-criteria results");
    }
    if (item.stage === "VERIFIED" && item.artifact_reference.toLowerCase().includes("self-review")) {
      throw new Error("Verification artifact cannot be a self-review");
    }
    plan.stageIndex = stageIndex;
    plan.latestId = turnId(turn);
    plan.latestStage = item.stage;
  }
}

/** Validate a new model reply against already persisted turns before it is appended. */
export function validateBuilderReplyAgainstHistory(reply, history, speaker, promptTurnId) {
  if (!reply || typeof reply !== "object") throw new TypeError("Builder reply is required");
  const type = reply.turn_type;
  const replyTo = reply.reply_to_turn_id;
  const recipient = reply.recipient;
  const byId = new Map();
  if (promptTurnId != null) {
    byId.set(nonEmpty(promptTurnId, "Task prompt turn identity", 256), {
      turn: { speaker: "USER", type: "CONTRIBUTION", recipient: null }, index: -1
    });
  }
  for (let index = 0; index < history.length; index += 1) {
    const id = turnId(history[index]);
    if (id) {
      if (byId.has(id)) throw new Error("Task prompt and run history share a turn identity");
      byId.set(id, { turn: history[index], index });
    }
  }
  validateReplyShape(type, speaker, recipient, replyTo, reply.evidence_status);
  if (REFERENCE_TYPES.has(type)) {
    const target = byId.get(replyTo);
    if (!target || target.index >= history.length) {
      throw new Error(type + " must reference an earlier turn in this run's thread");
    }
    validateReferenceRelationship(type, speaker, recipient, target.turn);
  }
  if (type === "CORRECTION_PLAN") {
    const item = parseObject(reply.body, "Correction plan");
    for (const field of ["correction_id", "incident_turn_id", "objective", "acceptance_criteria", "steps", "owner", "rollback_condition"]) {
      nonEmpty(item[field], "Correction plan " + field, 4096);
    }
    if (item.status !== "PROPOSED" || reply.evidence_status !== "PROPOSED") {
      throw new Error("Correction plan status and evidence_status must remain PROPOSED");
    }
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
      .map(turn => parseObject(turnBody(turn), "Prior correction update").stage)
      .filter(stage => stage !== "ADOPTED");
    const nextStage = CORRECTION_STAGES[completedStages.length];
    if (item.stage !== nextStage) throw new Error("Correction stages must advance exactly IMPLEMENTED, TESTED, VERIFIED");
    const incident = byId.get(item.incident_turn_id)?.turn;
    if (item.stage === "VERIFIED" && (speaker !== "BALDR" || speaker === incident?.speaker)) {
      throw new Error("Only unaffected Baldr may issue VERIFIED");
    }
    if (reply.evidence_status !== item.stage) {
      throw new Error("Correction update evidence_status must match its lifecycle stage");
    }
    if (item.stage === "IMPLEMENTED") parseEvidenceReference(item.artifact_reference, "COMMIT", item.stage);
    if (item.stage === "TESTED" || item.stage === "VERIFIED") {
      parseEvidenceReference(item.artifact_reference, "ACTIONS_RUN", item.stage);
    }
    const evidence = nonEmpty(item.evidence, "Correction update evidence", 4096);
    const criteria = nonEmpty(item.acceptance_criteria_result, "Correction update acceptance_criteria_result", 4096);
    nonEmpty(item.unresolved, "Correction update unresolved", 4096);
    if (evidence.length < 8 || criteria.length < 8) {
      throw new Error("Correction stage requires substantive evidence and acceptance-criteria results");
    }
    if (item.stage === "VERIFIED" && item.artifact_reference.toLowerCase().includes("self-review")) {
      throw new Error("Verification artifact cannot be a self-review");
    }
  }
  return reply;
}
