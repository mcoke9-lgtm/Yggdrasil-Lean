const STATES = Object.freeze([
  "REQUESTED",
  "PREPARED",
  "SENT",
  "ACCEPTED_UNCONFIRMED",
  "SOURCE_FENCED",
  "COMMIT_SENT",
  "TRANSFERRED",
  "REJECTED",
  "PAUSED",
  "CANCEL_PENDING",
  "RECOVERY_REQUIRED"
]);

const EXECUTION_FENCE_STATES = new Set([
  "SOURCE_FENCED",
  "COMMIT_SENT",
  "PAUSED",
  "CANCEL_PENDING",
  "RECOVERY_REQUIRED"
]);

const TRANSITIONS = Object.freeze({
  REQUESTED: new Set(["PREPARED", "REJECTED", "PAUSED", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  PREPARED: new Set(["SENT", "REJECTED", "PAUSED", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  SENT: new Set(["ACCEPTED_UNCONFIRMED", "REJECTED", "PAUSED", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  ACCEPTED_UNCONFIRMED: new Set(["SOURCE_FENCED", "REJECTED", "PAUSED", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  SOURCE_FENCED: new Set(["COMMIT_SENT", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  COMMIT_SENT: new Set(["TRANSFERRED", "CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  PAUSED: new Set(["CANCEL_PENDING", "RECOVERY_REQUIRED"]),
  CANCEL_PENDING: new Set(["REJECTED", "RECOVERY_REQUIRED"]),
  RECOVERY_REQUIRED: new Set(["PAUSED", "CANCEL_PENDING", "REJECTED", "SOURCE_FENCED", "COMMIT_SENT", "TRANSFERRED"])
});

function requireNonEmptyString(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${label} must be a non-empty string`);
  }
}

function requirePositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new TypeError(`${label} must be a positive safe integer`);
  }
}

function cloneRecord(record) {
  return structuredClone(record);
}

export function createHandoff(input) {
  if (!input || typeof input !== "object") throw new TypeError("Handoff input is required");
  for (const field of ["handoffId", "taskId", "sourceId", "destinationId", "payloadDigest", "authorizationRef"]) {
    requireNonEmptyString(input[field], field);
  }
  if (input.sourceId === input.destinationId) {
    throw new TypeError("Source and destination must be different schedulers");
  }
  requirePositiveInteger(input.taskRevision, "taskRevision");
  requirePositiveInteger(input.generation, "generation");

  return {
    handoffId: input.handoffId,
    taskId: input.taskId,
    taskRevision: input.taskRevision,
    generation: input.generation,
    sourceId: input.sourceId,
    destinationId: input.destinationId,
    payloadDigest: input.payloadDigest,
    authorizationRef: input.authorizationRef,
    state: "REQUESTED",
    ownerId: input.sourceId,
    preparedReceiptId: null,
    committedReceiptId: null,
    createdAt: input.createdAt ?? null,
    updatedAt: input.createdAt ?? null
  };
}

export function assertReceiptMatches(record, receipt, expectedStatus) {
  if (!record || !receipt || typeof receipt !== "object") {
    throw new TypeError("A handoff record and receipt are required");
  }
  const fields = ["handoffId", "taskId", "taskRevision", "generation", "sourceId", "destinationId", "payloadDigest"];
  for (const field of fields) {
    if (receipt[field] !== record[field]) {
      throw new Error(`Receipt mismatch: ${field}`);
    }
  }
  if (receipt.status !== expectedStatus) {
    throw new Error(`Receipt status must be ${expectedStatus}`);
  }
  requireNonEmptyString(receipt.receiptId, "receiptId");
  return true;
}

/**
 * Apply one protocol event and return a new record. The input record is never
 * mutated. Ordinary events must use the current generation. A newer generation
 * is required for pause/cancel commands so stale commands cannot overwrite
 * newer task intent.
 */
export function applyHandoffEvent(record, event) {
  if (!record || !event || typeof event !== "object") {
    throw new TypeError("A handoff record and event are required");
  }
  if (!STATES.includes(record.state)) throw new TypeError("Unknown handoff state");
  requireNonEmptyString(event.type, "event.type");

  const commandWithNewGeneration = event.type === "PAUSE" || event.type === "CANCEL";
  if (commandWithNewGeneration) {
    requirePositiveInteger(event.generation, "event.generation");
    if (event.generation <= record.generation) {
      throw new Error("Pause or cancel must use a newer generation");
    }
  } else if (event.generation !== record.generation) {
    throw new Error("Stale or mismatched handoff generation");
  }

  const next = cloneRecord(record);
  const transition = (state) => {
    if (!TRANSITIONS[record.state]?.has(state)) {
      throw new Error(`Invalid handoff transition: ${record.state} -> ${state}`);
    }
    next.state = state;
  };

  switch (event.type) {
    case "PREPARE":
      transition("PREPARED");
      break;
    case "SEND":
      transition("SENT");
      break;
    case "DESTINATION_PREPARED":
      assertReceiptMatches(record, event.receipt, "PREPARED");
      transition("ACCEPTED_UNCONFIRMED");
      next.preparedReceiptId = event.receipt.receiptId;
      break;
    case "SOURCE_FENCED":
      transition("SOURCE_FENCED");
      break;
    case "SEND_COMMIT":
      transition("COMMIT_SENT");
      break;
    case "DESTINATION_COMMITTED":
      assertReceiptMatches(record, event.receipt, "COMMITTED");
      transition("TRANSFERRED");
      next.committedReceiptId = event.receipt.receiptId;
      next.ownerId = record.destinationId;
      break;
    case "REJECT":
      if (record.state === "CANCEL_PENDING") {
        throw new Error("Cancellation cannot be rejected or cleared without destination proof");
      }
      transition("REJECTED");
      break;
    case "PAUSE":
      transition("PAUSED");
      next.generation = event.generation;
      break;
    case "CANCEL":
      transition("CANCEL_PENDING");
      next.generation = event.generation;
      break;
    case "REQUIRE_RECOVERY":
      transition("RECOVERY_REQUIRED");
      break;
    case "RESOLVE_TO_SOURCE":
      if (record.state !== "RECOVERY_REQUIRED" && record.state !== "CANCEL_PENDING") {
        throw new Error("Source ownership can only be restored during recovery or cancellation");
      }
      if (!event.receipt) {
        throw new Error("Source restoration requires destination proof that the handoff was not committed");
      }
      assertReceiptMatches(record, event.receipt, "CANCELLED");
      transition("REJECTED");
      next.ownerId = record.sourceId;
      break;
    case "RESOLVE_TO_DESTINATION":
      if (record.state !== "RECOVERY_REQUIRED" || !event.receipt) {
        throw new Error("Destination resolution requires recovery and a committed receipt");
      }
      assertReceiptMatches(record, event.receipt, "COMMITTED");
      transition("TRANSFERRED");
      next.committedReceiptId = event.receipt.receiptId;
      next.ownerId = record.destinationId;
      break;
    default:
      throw new Error(`Unknown handoff event: ${event.type}`);
  }

  if (event.at !== undefined) next.updatedAt = event.at;
  return next;
}

export function canExecuteTask({ localSchedulerId, ownerId, generation, currentGeneration, handoffState = null }) {
  requireNonEmptyString(localSchedulerId, "localSchedulerId");
  requireNonEmptyString(ownerId, "ownerId");
  requirePositiveInteger(generation, "generation");
  requirePositiveInteger(currentGeneration, "currentGeneration");
  if (generation !== currentGeneration || localSchedulerId !== ownerId) return false;
  if (handoffState !== null && (!STATES.includes(handoffState) || EXECUTION_FENCE_STATES.has(handoffState))) {
    return false;
  }
  return true;
}

export function isHandoffState(value) {
  return STATES.includes(value);
}

export const HANDOFF_STATES = STATES;
