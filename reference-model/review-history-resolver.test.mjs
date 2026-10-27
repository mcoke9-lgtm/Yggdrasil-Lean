import test from "node:test";
import assert from "node:assert/strict";
import { auditDataset, hasParentCycle, resolveRecordReferences, Outcome, mapGraphOutcomeForLean } from "./review-history-resolver.mjs";
import { pilotSnapshot } from "./pilot-snapshot.mjs";
import { parentGraphFixtures } from "./parent-graph-fixtures.mjs";
import { buildGraphInputCertificate, serializeGraphInputCertificate } from "./graph-input-certificate.mjs";

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


test("audits the retrieved ten-record pilot snapshot without silent fallback", () => {
  assert.equal(pilotSnapshot.length, 10);
  const result = auditDataset(pilotSnapshot);
  assert.deepEqual(result.duplicateRecordIds, []);
  assert.deepEqual(result.duplicateBranchSequences, []);
  assert.equal(result.parentCycle, "NO_CYCLE");
  const missingAnchor = result.records.find((r) => r.recordId === "AF-PILOT-005");
  assert.equal(missingAnchor.sourceAnchor, Outcome.MISSING_SOURCE_ANCHOR);
  assert.equal(result.records.find((r) => r.recordId === "AF-PILOT-004").sourceAnchor,
    Outcome.RESOLVED);
  assert.equal(result.records.find((r) => r.recordId === "AF-PILOT-002").revision,
    Outcome.RESOLVED);
  assert.equal(result.records.find((r) => r.recordId === "AF-PILOT-003").revision,
    Outcome.RESOLVED);
});


test("audits malformed record IDs and malformed branch-local sequence keys", () => {
  const result = auditDataset([
    { recordId: " GOOD ", branchId: "main", sequence: 1 },
    { recordId: "B", branchId: " ", sequence: 2 },
    { recordId: "C", branchId: "main", sequence: 0 },
    { recordId: 17, branchId: "main", sequence: 3 },
  ]);
  assert.equal(result.invalidRecordIds.length, 2);
  assert.equal(result.invalidBranchSequences.length, 2);
});

test("duplicate IDs are ambiguous for parent, source anchor, and revision references", () => {
  const records = [
    { recordId: "T", branchId: "main", sequence: 1 },
    { recordId: "T", branchId: "main", sequence: 2 },
  ];
  const record = { recordId: "X", parentRecordId: "T", sourceAnchorId: "T",
    sourceBranchId: "main", revisionOfId: "T" };
  const result = resolveRecordReferences(records, record);
  assert.equal(result.parent, Outcome.AMBIGUOUS_PARENT);
  assert.equal(result.sourceAnchor, Outcome.AMBIGUOUS_SOURCE_ANCHOR);
  assert.equal(result.revision, Outcome.AMBIGUOUS_REVISION_TARGET);
});

test("detects self-cycles and cycles in disconnected components", () => {
  assert.equal(hasParentCycle([{ recordId: "A", parentRecordId: "A" }]), true);
  assert.equal(hasParentCycle([
    { recordId: "A" },
    { recordId: "B", parentRecordId: "C" },
    { recordId: "C", parentRecordId: "B" },
  ]), true);
});

test("does not mistake a long acyclic parent chain for a cycle", () => {
  const records = Array.from({ length: 500 }, (_, i) => ({
    recordId: `R${i}`, parentRecordId: i === 0 ? undefined : `R${i - 1}`,
  }));
  assert.equal(hasParentCycle(records), false);
});


test("marks parent graph indeterminate when record identities are duplicated", async () => {
  const { analyzeParentGraph } = await import("./review-history-resolver.mjs");
  assert.equal(analyzeParentGraph([
    { recordId: "A", parentRecordId: "B" },
    { recordId: "A", parentRecordId: null },
    { recordId: "B", parentRecordId: null },
  ]), "INDETERMINATE");
});

test("marks parent graph indeterminate for malformed identities or parent references", async () => {
  const { analyzeParentGraph } = await import("./review-history-resolver.mjs");
  assert.equal(analyzeParentGraph([{ recordId: " A " }]), "INDETERMINATE");
  assert.equal(analyzeParentGraph([{ recordId: "A", parentRecordId: 12 }]), "INDETERMINATE");
});

test("distinguishes a definite cycle from an indeterminate graph", async () => {
  const { analyzeParentGraph } = await import("./review-history-resolver.mjs");
  assert.equal(analyzeParentGraph([
    { recordId: "A", parentRecordId: "B" },
    { recordId: "B", parentRecordId: "A" },
  ]), "CYCLE");
  assert.equal(analyzeParentGraph([
    { recordId: "A", parentRecordId: "MISSING" },
  ]), "INDETERMINATE");
});


test("bridge refuses to promote a cycle status without a validated witness", () => {
  assert.deepEqual(mapGraphOutcomeForLean("CYCLE"), {
    leanOutcome: "indeterminate", accepted: false,
    reason: "cycle-witness-not-provided-or-validated",
  });
  assert.equal(mapGraphOutcomeForLean("CYCLE", { validatedCycleWitness: true }).leanOutcome, "cycle");
});

test("bridge preserves indeterminate and refuses uncertified no-cycle claims", () => {
  assert.equal(mapGraphOutcomeForLean("INDETERMINATE").leanOutcome, "indeterminate");
  assert.equal(mapGraphOutcomeForLean("NO_CYCLE").leanOutcome, "indeterminate");
  assert.equal(mapGraphOutcomeForLean("NO_CYCLE", {
    identityIntegrityCertain: true, edgeListComplete: true, acyclicityProven: true,
  }).leanOutcome, "noCycle");
  assert.equal(mapGraphOutcomeForLean("NO_CYCLE", {
    identityIntegrityCertain: true, edgeListComplete: false, acyclicityProven: true,
  }).leanOutcome, "indeterminate");
});

test("bridge maps unknown runtime outcomes to indeterminate", () => {
  assert.equal(mapGraphOutcomeForLean("SURPRISE").leanOutcome, "indeterminate");
});


test("shared parent-graph fixtures document runtime and conservative bridge outcomes", async () => {
  const { analyzeParentGraph } = await import("./review-history-resolver.mjs");
  for (const fixture of parentGraphFixtures) {
    assert.equal(analyzeParentGraph(fixture.records), fixture.runtime, fixture.name);
    assert.equal(
      mapGraphOutcomeForLean(fixture.runtime).leanOutcome,
      fixture.bridgeWithoutEvidence,
      fixture.name,
    );
  }
});

test("a cycle outcome can bridge only when the adapter validates a concrete witness", () => {
  const cycle = parentGraphFixtures.find((fixture) => fixture.runtime === "CYCLE");
  assert.ok(cycle, "at least one cycle fixture is required");
  assert.equal(mapGraphOutcomeForLean("CYCLE", {
    validatedCycleWitness: true,
  }).leanOutcome, "cycle");
});

test("NO_CYCLE bridge rejects missing-parent fixture without completeness certificate", () => {
  const missingParent = parentGraphFixtures.find((fixture) => fixture.name === "missing parent target");
  assert.equal(missingParent.runtime, "INDETERMINATE");
  assert.equal(mapGraphOutcomeForLean(missingParent.runtime).accepted, true);
  assert.equal(mapGraphOutcomeForLean(missingParent.runtime).leanOutcome, "indeterminate");
});


test("graph certificate binds deterministic vertices and edges to source metadata", () => {
  const records = [
    { recordId: "B", parentRecordId: "A" },
    { recordId: "A", parentRecordId: null },
  ];
  const options = { sourceReadId: "snapshot-001", sourceDigest: "sha256:source", sourceCompletenessVerified: true };
  const certificate = buildGraphInputCertificate(records, options);
  const reversed = buildGraphInputCertificate([...records].reverse(), options);
  assert.equal(certificate.certificateReady, true);
  assert.deepEqual(certificate.vertices, [
    { recordId: "A", vertexId: 1 }, { recordId: "B", vertexId: 2 },
  ]);
  assert.deepEqual(certificate.edges, [{ childVertexId: 2, parentVertexId: 1 }]);
  assert.equal(certificate.graphDigest, reversed.graphDigest);
  assert.equal(certificate.sourceReadId, "snapshot-001");
});

test("graph certificate does not infer source completeness from a well-formed graph", () => {
  const certificate = buildGraphInputCertificate([
    { recordId: "A", parentRecordId: null },
  ], { sourceReadId: "snapshot-002", sourceDigest: "sha256:source" });
  assert.equal(certificate.identityIntegrityCertain, true);
  assert.equal(certificate.sourceCompletenessVerified, false);
  assert.equal(certificate.certificateReady, false);
});

test("graph certificate makes duplicate IDs and unresolved parents explicit", () => {
  const certificate = buildGraphInputCertificate([
    { recordId: "A", parentRecordId: null },
    { recordId: "A", parentRecordId: null },
    { recordId: "C", parentRecordId: "MISSING" },
    { recordId: " B ", parentRecordId: null },
  ], { sourceReadId: "snapshot-003", sourceDigest: "sha256:source", sourceCompletenessVerified: true });
  assert.equal(certificate.certificateReady, false);
  assert.equal(certificate.identityIntegrityCertain, false);
  assert.ok(certificate.findings.some((finding) => finding.code === "DUPLICATE_RECORD_ID"));
  assert.ok(certificate.findings.some((finding) => finding.code === "MALFORMED_RECORD_ID"));
  assert.ok(certificate.findings.some((finding) => finding.code === "UNRESOLVED_PARENT_REFERENCE"));
});

test("graph certificate rejects absent or malformed source identity metadata", () => {
  const certificate = buildGraphInputCertificate([{ recordId: "A" }], {
    sourceReadId: " read ", sourceDigest: "", sourceCompletenessVerified: true,
  });
  assert.equal(certificate.certificateReady, false);
  assert.ok(certificate.findings.some((finding) => finding.code === "MISSING_OR_MALFORMED_SOURCE_READ_ID"));
  assert.ok(certificate.findings.some((finding) => finding.code === "MISSING_OR_MALFORMED_SOURCE_DIGEST"));
});

test("serialized graph handoff is versioned, deterministic, and binds mapping to Lean input", () => {
  const options = {
    sourceReadId: "read-001",
    sourceDigest: "source-sha256:example",
    sourceCompletenessVerified: true,
  };
  const certificate = buildGraphInputCertificate([
    { recordId: "B", parentRecordId: "A" },
    { recordId: "A", parentRecordId: null },
  ], options);
  const reordered = buildGraphInputCertificate([
    { recordId: "A", parentRecordId: null },
    { recordId: "B", parentRecordId: "A" },
  ], options);
  const serialized = serializeGraphInputCertificate(certificate);
  assert.equal(serialized, serializeGraphInputCertificate(reordered));
  const artifact = JSON.parse(serialized);
  assert.equal(artifact.schema, "yggdrasil-parent-graph-v1");
  assert.deepEqual(artifact.sourceVertexBindings, [
    { recordId: "A", vertexId: 1 },
    { recordId: "B", vertexId: 2 },
  ]);
  assert.deepEqual(artifact.graphInput, {
    vertices: [1, 2],
    edges: [[2, 1]],
  });
  assert.equal(artifact.graphDigest, certificate.graphDigest);
  assert.equal(artifact.certificateReady, true);
});

test("serializer rejects missing graph binding fields", () => {
  assert.throws(() => serializeGraphInputCertificate({ graphDigest: "x" }), TypeError);
});
