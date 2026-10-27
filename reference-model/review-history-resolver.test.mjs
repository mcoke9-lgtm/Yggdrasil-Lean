import test from "node:test";
import assert from "node:assert/strict";
import { auditDataset, hasParentCycle, resolveRecordReferences, Outcome } from "./review-history-resolver.mjs";

const base = [
  { recordId: "A", branchId: "main", sequence: 1 },
  { recordId: "B", branchId: "main", sequence: 2, parentRecordId: "A", revisionOfId: "A" },
  { recordId: "C", branchId: "fork", sequence: 1, parentRecordId: "B",
    sourceBranchId: "main", sourceAnchorId: "A" },
];

test("resolves valid parent, source anchor, and revision independently", () => {
  const result = resolveRecordReferences(base, base[2]);
  assert.equal(result.parent, Outcome.RESOLVED);
  assert.equal(result.sourceAnchor, Outcome.RESOLVED);
  assert.equal(resolveRecordReferences(base, base[1]).revision, Outcome.RESOLVED);
});

test("reports missing parent, source anchor, and revision target explicitly", () => {
  const r = { recordId: "X", branchId: "main", sequence: 4, parentRecordId: "NO-PARENT",
    sourceBranchId: "main", sourceAnchorId: "NO-ANCHOR", revisionOfId: "NO-REVISION" };
  const result = resolveRecordReferences(base, r);
  assert.equal(result.parent, Outcome.MISSING_PARENT);
  assert.equal(result.sourceAnchor, Outcome.MISSING_SOURCE_ANCHOR);
  assert.equal(result.revision, Outcome.MISSING_REVISION_TARGET);
});

test("reports duplicate IDs as ambiguous instead of selecting a record", () => {
  const records = [...base, { recordId: "A", branchId: "other", sequence: 1 }];
  assert.equal(resolveRecordReferences(records, { recordId: "X", parentRecordId: "A" }).parent,
    Outcome.AMBIGUOUS_PARENT);
  assert.equal(resolveRecordReferences(records, { recordId: "X", sourceAnchorId: "A",
    sourceBranchId: "main" }).sourceAnchor, Outcome.AMBIGUOUS_SOURCE_ANCHOR);
  assert.equal(resolveRecordReferences(records, { recordId: "X", revisionOfId: "A" }).revision,
    Outcome.AMBIGUOUS_REVISION_TARGET);
});

test("rejects source anchor that resolves outside the declared source branch", () => {
  assert.equal(resolveRecordReferences(base, { recordId: "X", sourceAnchorId: "A",
    sourceBranchId: "not-main" }).sourceAnchor, Outcome.SOURCE_BRANCH_MISMATCH);
});

test("flags whitespace and non-string identifiers without trimming them", () => {
  assert.equal(resolveRecordReferences(base, { recordId: "X", parentRecordId: " A" })
    .malformedReference, Outcome.MALFORMED_REFERENCE);
  assert.equal(resolveRecordReferences(base, { recordId: "X", revisionOfId: 7 })
    .malformedReference, Outcome.MALFORMED_REFERENCE);
});

test("empty optional references are not applicable", () => {
  const result = resolveRecordReferences(base, { recordId: "X", parentRecordId: "",
    sourceAnchorId: null, revisionOfId: undefined });
  assert.equal(result.parent, Outcome.NOT_APPLICABLE);
  assert.equal(result.sourceAnchor, Outcome.NOT_APPLICABLE);
  assert.equal(result.revision, Outcome.NOT_APPLICABLE);
});

test("detects parent cycles", () => {
  assert.equal(hasParentCycle([{ recordId: "A", parentRecordId: "B" },
    { recordId: "B", parentRecordId: "A" }]), true);
  assert.equal(hasParentCycle(base), false);
});

test("audits duplicate application IDs and duplicate branch sequences", () => {
  const result = auditDataset([...base, { recordId: "D", branchId: "main", sequence: 2 }]);
  assert.deepEqual(result.duplicateRecordIds, []);
  assert.deepEqual(result.duplicateBranchSequences, [["main", 2]]);
});

test("matches the pilot's known missing-anchor fixture without fallback", () => {
  const pilot = [
    { recordId: "AF-PILOT-001", branchId: "chatgpt-first-integration", sequence: 1 },
    { recordId: "AF-PILOT-004", branchId: "chatgpt-first-integration-fork-test", sequence: 1,
      sourceBranchId: "chatgpt-first-integration", sourceAnchorId: "AF-PILOT-001" },
    { recordId: "AF-PILOT-005", branchId: "missing-anchor-test", sequence: 1,
      sourceBranchId: "chatgpt-first-integration", sourceAnchorId: "AF-ANCHOR-DOES-NOT-EXIST" },
  ];
  assert.equal(resolveRecordReferences(pilot, pilot[1]).sourceAnchor, Outcome.RESOLVED);
  assert.equal(resolveRecordReferences(pilot, pilot[2]).sourceAnchor, Outcome.MISSING_SOURCE_ANCHOR);
});
