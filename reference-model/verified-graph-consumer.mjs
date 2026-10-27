import { consumeVerifiedGraphInputArtifact } from "./graph-input-certificate.mjs";
import { analyzeParentGraph } from "./review-history-resolver.internal.mjs";

/**
 * Gated reference-model graph-analysis entry point.
 * Callers cannot inject an alternate analyzer; this module runs the internal
 * analyzer only after artifact and completeness checks pass.
 */
export function analyzeVerifiedGraphInput(serialized, options) {
  const gate = consumeVerifiedGraphInputArtifact(serialized, options);
  if (!gate.accepted) return { accepted: false, code: gate.code };
  return {
    accepted: true,
    code: "ANALYSIS_COMPLETED",
    sourceReadId: gate.sourceReadId,
    sourceDigest: gate.sourceDigest,
    graphDigest: gate.graphDigest,
    graphInput: gate.graphInput,
    analysis: analyzeParentGraph(gate.graphInput.vertices.map((vertex) => {
      const parentEdge = gate.graphInput.edges.find(([child]) => child === vertex);
      return { recordId: String(vertex), parentRecordId: parentEdge ? String(parentEdge[1]) : null };
    })),
  };
}
