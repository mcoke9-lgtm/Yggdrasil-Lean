# Graph-Analysis Gate Bypass Audit 001

**Status:** repository-source audit completed for commit `2d163c2f3a721c0b760c7eb0a94466d5165a326e`; inventory is documentation, not a production security guarantee.

## Scope inspected

- `reference-model/verified-graph-consumer.mjs`
- `reference-model/graph-input-certificate.mjs`
- `reference-model/review-history-resolver.mjs`
- `reference-model/review-history-resolver.test.mjs`
- `YggdrasilLean/ParentGraphAudit.lean`
- `YggdrasilLean/ParentGraphAcyclicity.lean`
- `YggdrasilLean/GraphInputEvidence.lean`
- `YggdrasilLean/ResolverGraphOutcomeMapping.lean`
- `.github/workflows/resolver-tests.yml`

## Entry-point inventory

| Entry point | Gate enforced? | Interpretation |
|---|---|---|
| `analyzeVerifiedGraphInput(serialized, options, analyzeGraph)` | Yes | Intended gated reference-model consumer. It does not invoke the callback unless artifact verification and completeness checks pass. |
| `consumeVerifiedGraphInputArtifact(...)` | Yes, as a validation function | Returns graph input only on success; it is a gate primitive, not itself an analysis operation. |
| `analyzeParentGraph(records)` | No | Exported low-level resolver utility; accepts raw records. |
| `hasParentCycle(records)` | No | Exported convenience wrapper over raw records. |
| `auditDataset(records)` | No | Exported audit utility; invokes parent-graph analysis on raw records. |
| `resolveRecordReferences(records, record)` | No | Exported reference resolver; invokes parent-graph analysis on raw records. |
| Lean graph classifiers and predicates | No runtime artifact gate | Pure formal/reference definitions consume supplied Lean values. `GraphInputEvidence` specifies structural premises, but Lean does not parse the JavaScript artifact or independently verify its SHA-256 digest. |

## Findings and disposition

1. **Raw JS bypasses remain by design.** These functions are useful for isolated reference-model work and tests, but callers can bypass the artifact gate by importing them directly. No production application or deployed runtime was found in the inspected repository tree that wires graph analysis to an external source.
2. **The gated wrapper is not a repository-wide access-control boundary.** It is an API convention in a reference model. JavaScript modules do not prevent another module from importing the low-level exports.
3. **Lean is a separate formal layer.** The graph gate does not and cannot automatically wrap theorem definitions. Structural agreement with a supplied graph is not proof that the source graph is complete, nor proof of JS/Lean semantic equivalence.
4. **No bypass was silently relabeled as protected.** This audit documents each unguarded entry point instead of claiming that every path is blocked.

## Recommended follow-up before production use

- Create a production-facing module that alone owns source retrieval, artifact verification, and graph-analysis dispatch.
- Keep raw graph-analysis functions private to that module or explicitly label them as unsafe/internal; update tests to use test-only access if necessary.
- Add an architectural test or lint rule that rejects production imports of raw graph-analysis exports.
- Bind the independent completeness attestation to a documented, reviewable source-read procedure; do not treat a boolean supplied by an untrusted caller as proof.
- Define how the verified graph digest and source-read identity are carried into the Lean proof/result record. Do not claim cryptographic verification in Lean until implemented and tested.
- Run integration tests against the real selected backend before calling the adapter production-ready.

## Verification boundaries

The existing resolver-test workflow exercises the reference-model tests; the Lean workflow compiles Lean definitions. Neither is a deployment test or a proof that no alternate path exists in a future consumer repository. The audit reflects the source tree and commit named above and must be repeated after architectural changes.
