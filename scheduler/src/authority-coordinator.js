import { createHash } from "node:crypto";
import { withStoreLock } from "./store.js";

const ID = /^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/;
const FINGERPRINT = /^[a-f0-9]{64}$/;

function digest(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
function requireId(value, label) {
  if (typeof value !== "string" || !ID.test(value)) throw new TypeError(`${label} must be a stable identifier`);
}
function clone(value) { return structuredClone(value); }

/**
 * Durable local reference coordinator.
 * Its CAS is serialized only for callers sharing this store object in one process.
 * It is NOT a distributed authority service and must not be used to enable failover.
 */
export class AuthorityCoordinator {
  constructor({ store, clock = () => new Date() }) {
    if (!store || typeof store.load !== "function" || typeof store.save !== "function") {
      throw new TypeError("A compatible persistent store is required");
    }
    this.store = store;
    this.clock = clock;
  }

  async initialize({ taskId, owner, taskDefinitionFingerprint }) {
    requireId(taskId, "taskId");
    requireId(owner, "owner");
    if (!FINGERPRINT.test(taskDefinitionFingerprint || "")) {
      throw new TypeError("taskDefinitionFingerprint must be a SHA-256 hex digest");
    }
    return withStoreLock(this.store, async () => {
      const state = await this.store.load();
      const existing = state.authorities.find(item => item.taskId === taskId);
      if (existing) {
        if (existing.initialOwner === owner && existing.taskDefinitionFingerprint === taskDefinitionFingerprint) return clone(existing);
        throw new Error("Authority is already initialized with a different identity or owner");
      }
      const record = {
        taskId, owner, initialOwner: owner, epoch: 1, version: 1, taskDefinitionFingerprint,
        initializedAt: this.clock().toISOString(), updatedAt: this.clock().toISOString()
      };
      state.authorities.push(record);
      await this.store.save(state);
      return clone(record);
    });
  }

  async compareAndSwap({
    taskId, transferId, expectedEpoch, expectedOwner, newOwner, taskDefinitionFingerprint
  }) {
    requireId(taskId, "taskId");
    requireId(transferId, "transferId");
    requireId(expectedOwner, "expectedOwner");
    requireId(newOwner, "newOwner");
    if (newOwner === expectedOwner) throw new TypeError("newOwner must differ from expectedOwner");
    if (!Number.isSafeInteger(expectedEpoch) || expectedEpoch < 1) {
      throw new TypeError("expectedEpoch must be a positive safe integer");
    }
    if (!FINGERPRINT.test(taskDefinitionFingerprint || "")) {
      throw new TypeError("taskDefinitionFingerprint must be a SHA-256 hex digest");
    }
    const request = { taskId, transferId, expectedEpoch, expectedOwner, newOwner, taskDefinitionFingerprint };
    const requestFingerprint = digest(request);

    return withStoreLock(this.store, async () => {
      const state = await this.store.load();
      const prior = state.authorityTransfers.find(item => item.transferId === transferId);
      if (prior) {
        if (prior.requestFingerprint !== requestFingerprint) {
          throw new Error("transferId already exists with a different request");
        }
        return { transfer: clone(prior), duplicate: true };
      }
      const current = state.authorities.find(item => item.taskId === taskId);
      if (!current) throw new Error(`Authority is not initialized for task: ${taskId}`);
      if (current.taskDefinitionFingerprint !== taskDefinitionFingerprint) {
        throw new Error("Task definition fingerprint does not match current authority");
      }
      if (current.epoch !== expectedEpoch || current.owner !== expectedOwner || current.version !== expectedEpoch) {
        throw new Error("Authority compare-and-swap conflict; current authority differs from expected state");
      }

      const now = this.clock().toISOString();
      const nextEpoch = current.epoch + 1;
      const transfer = {
        transferId, taskId, sourceOwner: current.owner, destinationOwner: newOwner,
        priorEpoch: current.epoch, grantedEpoch: nextEpoch, taskDefinitionFingerprint,
        requestFingerprint, status: "AUTHORITY_ADVANCED", grantedAt: now,
        confirms: "LOCAL_REFERENCE_GRANT_ONLY"
      };
      const next = {
        ...current, owner: newOwner, epoch: nextEpoch, version: nextEpoch,
        updatedAt: now, lastTransferId: transferId
      };
      state.authorities = state.authorities.map(item => item.taskId === taskId ? next : item);
      state.authorityTransfers.push(transfer);
      await this.store.save(state);
      return { transfer: clone(transfer), duplicate: false };
    });
  }

  async get(taskId) {
    requireId(taskId, "taskId");
    const state = await this.store.load();
    const record = state.authorities.find(item => item.taskId === taskId);
    return record ? clone(record) : null;
  }

  async getTransfer(transferId) {
    requireId(transferId, "transferId");
    const state = await this.store.load();
    const transfer = state.authorityTransfers.find(item => item.transferId === transferId);
    return transfer ? clone(transfer) : null;
  }
}
