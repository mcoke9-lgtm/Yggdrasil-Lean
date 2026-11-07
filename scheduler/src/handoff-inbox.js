import { withStoreLock } from "./store.js";
import { createHash } from "node:crypto";

const ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/;

function clone(value) {
  return structuredClone(value);
}

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function digest(value) {
  return createHash("sha256").update(stable(value)).digest("hex");
}

function requiredId(value, label) {
  if (typeof value !== "string" || !ID_PATTERN.test(value)) {
    throw new TypeError(`${label} must be a non-empty stable identifier`);
  }
}

export class HandoffInbox {
  constructor({ store, clock = () => new Date(), destinationScheduler }) {
    if (!store || typeof store.load !== "function" || typeof store.save !== "function") {
      throw new TypeError("A compatible persistent store is required");
    }
    requiredId(destinationScheduler, "destinationScheduler");
    this.store = store;
    this.clock = clock;
    this.destinationScheduler = destinationScheduler;
    this.queue = Promise.resolve();
  }

  withLock(operation) {
    return withStoreLock(this.store, operation);
  }

  async receive(request) {
    return this.withLock(async () => {
      if (!request || typeof request !== "object") throw new TypeError("A handoff request is required");
      requiredId(request.handoffId, "handoffId");
      requiredId(request.sourceScheduler, "sourceScheduler");
      if (request.sourceScheduler === this.destinationScheduler) {
        throw new Error("A scheduler cannot hand off a task to itself");
      }
      if (!request.taskDefinition || typeof request.taskDefinition !== "object" || Array.isArray(request.taskDefinition)) {
        throw new TypeError("A complete taskDefinition object is required");
      }
      if (request.authorization === undefined || request.authorization === null) {
        throw new TypeError("Authorization evidence is required");
      }
      if (!request.sourceRejectionEvidence || typeof request.sourceRejectionEvidence !== "object") {
        throw new TypeError("Source rejection evidence is required");
      }

      const immutableRequest = {
        handoffId: request.handoffId,
        sourceScheduler: request.sourceScheduler,
        destinationScheduler: this.destinationScheduler,
        taskDefinition: clone(request.taskDefinition),
        taskDefinitionVersion: request.taskDefinitionVersion ?? null,
        authorization: clone(request.authorization),
        sourceRejectionEvidence: clone(request.sourceRejectionEvidence)
      };
      const requestFingerprint = digest(immutableRequest);
      const state = await this.store.load();
      if (!Array.isArray(state.handoffs)) throw new Error("Scheduler state has no handoff inbox");
      const existing = state.handoffs.find(item => item.handoffId === request.handoffId);
      if (existing) {
        if (existing.requestFingerprint !== requestFingerprint) {
          throw new Error("Handoff ID already exists with a different request fingerprint");
        }
        return { handoff: clone(existing), receipt: clone(existing.receipt), duplicate: true };
      }

      const persistedAt = this.clock().toISOString();
      const receipt = {
        receiptId: `receipt:${digest({ handoffId: request.handoffId, requestFingerprint }).slice(0, 32)}`,
        handoffId: request.handoffId,
        destinationScheduler: this.destinationScheduler,
        requestFingerprint,
        persistedAt,
        confirms: "HANDOFF_PERSISTED"
      };
      const handoff = {
        ...immutableRequest,
        requestFingerprint,
        status: "HANDOFF_PERSISTED",
        receivedAt: persistedAt,
        receipt
      };
      state.handoffs.push(handoff);
      await this.store.save(state);
      return { handoff: clone(handoff), receipt: clone(receipt), duplicate: false };
    });
  }


  async validate(handoffId, validator) {
    return this.withLock(async () => {
      requiredId(handoffId, "handoffId");
      if (typeof validator !== "function") throw new TypeError("A destination task validator is required");
      const state = await this.store.load();
      const handoff = state.handoffs.find(item => item.handoffId === handoffId);
      if (!handoff) throw new Error(`Handoff not found: ${handoffId}`);
      if (handoff.status === "HANDOFF_ACCEPTED" || handoff.status === "TASK_ACTIVATED") {
        throw new Error("An accepted handoff cannot be revalidated");
      }
      let validation;
      try {
        validation = await validator(clone(handoff.taskDefinition));
        if (!validation || validation.valid !== true || !validation.task) {
          throw new Error(validation?.reason || "Destination validator did not approve the task");
        }
        handoff.status = "HANDOFF_VALIDATED";
        handoff.validatedTask = clone(validation.task);
        handoff.validation = {
          outcome: "VALID",
          validatedAt: this.clock().toISOString(),
          validator: validation.validator || "destination-validator"
        };
      } catch (error) {
        handoff.status = "HANDOFF_BLOCKED";
        delete handoff.validatedTask;
        handoff.validation = {
          outcome: "BLOCKED",
          validatedAt: this.clock().toISOString(),
          reason: String(error?.message || error).slice(0, 1000)
        };
      }
      await this.store.save(state);
      return clone(handoff);
    });
  }

  async accept(handoffId, { acceptanceAuthorization } = {}) {
    return this.withLock(async () => {
      requiredId(handoffId, "handoffId");
      if (!acceptanceAuthorization || typeof acceptanceAuthorization !== "object" ||
          acceptanceAuthorization.granted !== true ||
          typeof acceptanceAuthorization.authority !== "string" ||
          !acceptanceAuthorization.authority.trim()) {
        throw new TypeError("Explicit acceptance authorization with granted=true and authority is required");
      }
      const state = await this.store.load();
      const handoff = state.handoffs.find(item => item.handoffId === handoffId);
      if (!handoff) throw new Error(`Handoff not found: ${handoffId}`);
      if (handoff.status !== "HANDOFF_VALIDATED" || !handoff.validatedTask) {
        throw new Error("Only a validated handoff can be accepted");
      }
      const acceptedAt = this.clock().toISOString();
      handoff.status = "HANDOFF_ACCEPTED";
      handoff.acceptance = {
        acceptedAt,
        authorization: clone(acceptanceAuthorization),
        confirms: "HANDOFF_ACCEPTED"
      };
      await this.store.save(state);
      return clone(handoff);
    });
  }

  async get(handoffId) {
    requiredId(handoffId, "handoffId");
    const state = await this.store.load();
    const handoff = state.handoffs.find(item => item.handoffId === handoffId);
    return handoff ? clone(handoff) : null;
  }

  async list({ limit = 100 } = {}) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new RangeError("limit must be 1–500");
    const state = await this.store.load();
    return clone(state.handoffs.slice(-limit).reverse());
  }
}
