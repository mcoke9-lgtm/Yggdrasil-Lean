/**
 * Shared edge-case fixtures for the JavaScript graph analyzer and its
 * conservative Lean-correspondence bridge.
 *
 * Expected runtime statuses document actual analyzer behavior; expected bridge
 * statuses document the stricter evidence contract, not formal equivalence.
 */
export const parentGraphFixtures = [
  {
    name: "well-formed acyclic chain",
    records: [
      { recordId: "A", parentRecordId: null },
      { recordId: "B", parentRecordId: "A" },
      { recordId: "C", parentRecordId: "B" },
    ],
    runtime: "NO_CYCLE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "directed two-node cycle",
    records: [
      { recordId: "A", parentRecordId: "B" },
      { recordId: "B", parentRecordId: "A" },
    ],
    runtime: "CYCLE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "missing parent target",
    records: [{ recordId: "A", parentRecordId: "MISSING" }],
    runtime: "INDETERMINATE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "malformed identity without cycle",
    records: [{ recordId: " A ", parentRecordId: null }],
    runtime: "INDETERMINATE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "duplicate identity without cycle",
    records: [
      { recordId: "A", parentRecordId: null },
      { recordId: "A", parentRecordId: null },
    ],
    runtime: "INDETERMINATE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "malformed parent reference without cycle",
    records: [{ recordId: "A", parentRecordId: 42 }],
    runtime: "INDETERMINATE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "cycle plus unrelated malformed identity",
    records: [
      { recordId: "A", parentRecordId: "B" },
      { recordId: "B", parentRecordId: "A" },
      { recordId: " BAD ", parentRecordId: null },
    ],
    runtime: "CYCLE",
    bridgeWithoutEvidence: "indeterminate",
  },
  {
    name: "cycle plus missing parent on disconnected record",
    records: [
      { recordId: "A", parentRecordId: "B" },
      { recordId: "B", parentRecordId: "A" },
      { recordId: "C", parentRecordId: "MISSING" },
    ],
    runtime: "CYCLE",
    bridgeWithoutEvidence: "indeterminate",
  },
];
