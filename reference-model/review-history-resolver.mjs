/**
 * Public, non-graph resolver contract.
 *
 * Raw-record graph analysis is intentionally not re-exported from this module.
 * Use analyzeVerifiedGraphInput from verified-graph-consumer.mjs for graph
 * analysis of serialized artifacts.
 */
export { Outcome, mapGraphOutcomeForLean } from "./review-history-resolver.internal.mjs";
