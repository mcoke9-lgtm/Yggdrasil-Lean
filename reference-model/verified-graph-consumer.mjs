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
    sourceVertexBindings: gate.sourceVertexBindings,
    analysisRecords: gate.sourceVertexBindings.map(({ recordId, vertexId }) => {
      const parentEdge = gate.graphInput.edges.find(([child]) => child === vertexId);
      const parentVertexId = parentEdge ? parentEdge[1] : null;
      const parentBinding = gate.sourceVertexBindings.find((binding) => binding.vertexId === parentVertexId);
      return { recordId, parentRecordId: parentBinding ? parentBinding.recordId : null };
    }),
    analysis: analyzeParentGraph(gate.sourceVertexBindings.map(({ recordId, vertexId }) => {
      const parentEdge = gate.graphInput.edges.find(([child]) => child === vertexId);
      const parentVertexId = parentEdge ? parentEdge[1] : null;
      const parentBinding = gate.sourceVertexBindings.find((binding) => binding.vertexId === parentVertexId);
      return { recordId, parentRecordId: parentBinding ? parentBinding.recordId : null };
    })),
  };
}
