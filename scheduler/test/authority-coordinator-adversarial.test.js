import test from "node:test";
import assert from "node:assert/strict";
import { AuthorityCoordinator } from "../src/authority-coordinator.js";

const fingerprint = "a".repeat(64);
const seed = { version: 3, tasks: [], runs: [], handoffs: [],
  authorities: [{ taskId: "task-partition", owner: "source", initialOwner: "source",
    epoch: 1, version: 1, taskDefinitionFingerprint: fingerprint,
    initializedAt: "2026-11-06T20:00:00.000Z", updatedAt: "2026-11-06T20:00:00.000Z" }],
  authorityTransfers: [] };
class SeparateClientStore {
  constructor(backend, gate) { this.backend = backend; this.gate = gate; }
  async load() { const snapshot = structuredClone(this.backend.state); await this.gate.arrive(); return snapshot; }
  async save(state) { this.backend.state = structuredClone(state); }
}
function twoReaderGate() {
  let arrivals = 0; let release;
  const barrier = new Promise(resolve => { release = resolve; });
  return { async arrive() { arrivals += 1; if (arrivals === 2) release(); await barrier; } };
}
test("ADVERSARIAL EXPOSURE: separate store clients can both grant the same epoch", async () => {
  const backend = { state: structuredClone(seed) }; const gate = twoReaderGate();
  const first = new AuthorityCoordinator({ store: new SeparateClientStore(backend, gate) });
  const second = new AuthorityCoordinator({ store: new SeparateClientStore(backend, gate) });
  const request = (transferId, newOwner) => ({ taskId: "task-partition", transferId,
    expectedEpoch: 1, expectedOwner: "source", newOwner, taskDefinitionFingerprint: fingerprint });
  const results = await Promise.all([first.compareAndSwap(request("transfer-a", "destination-a")),
    second.compareAndSwap(request("transfer-b", "destination-b"))]);
  assert.equal(results.length, 2);
  assert.equal(results[0].transfer.grantedEpoch, 2);
  assert.equal(results[1].transfer.grantedEpoch, 2);
  assert.equal(backend.state.authorities[0].epoch, 2);
  assert.equal(backend.state.authorityTransfers.length, 1);
});
test("ADVERSARIAL EXPOSURE: restoring an older snapshot reuses an authority epoch", async () => {
  const backend = { state: structuredClone(seed) };
  const store = { async load() { return structuredClone(backend.state); }, async save(state) { backend.state = structuredClone(state); } };
  const coordinator = new AuthorityCoordinator({ store });
  await coordinator.compareAndSwap({ taskId: "task-partition", transferId: "transfer-after-snapshot",
    expectedEpoch: 1, expectedOwner: "source", newOwner: "destination", taskDefinitionFingerprint: fingerprint });
  assert.equal(backend.state.authorities[0].epoch, 2);
  backend.state = structuredClone(seed);
  await coordinator.compareAndSwap({ taskId: "task-partition", transferId: "transfer-after-restore",
    expectedEpoch: 1, expectedOwner: "source", newOwner: "other-destination", taskDefinitionFingerprint: fingerprint });
  assert.equal(backend.state.authorities[0].epoch, 2);
  assert.equal(backend.state.authorities[0].owner, "other-destination");
});