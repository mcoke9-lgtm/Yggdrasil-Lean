import { consumeVerifiedGraphInputArtifact } from "./graph-input-certificate.mjs";

/**
 * The reference-model graph-analysis entry point.
 *
 * The analysis callback is never invoked unless the serialized artifact passes
 * the consumer gate. This is a reference-model boundary, not production wiring.
 */
export function analyzeVerifiedGraphInput(serialized, options, analyzeGraph) {
  if (typeof analyzeGraph !== "function") {
    return { accepted: false, code: "INVALID_ANALYSIS_CALLBACK" };
  }
  const gate = consumeVerifiedGraphInputArtifact(serialized, options);
  if (!gate.accepted) {
    return { accepted: false, code: gate.code };
  }
  return {
    accepted: true,
    code: "ANALYSIS_COMPLETED",
    sourceReadId: gate.sourceReadId,
    sourceDigest: gate.sourceDigest,
    graphDigest: gate.graphDigest,
    analysis: analyzeGraph(gate.graphInput),
  };
}
