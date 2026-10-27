# JavaScript–Lean Graph Input Evidence Contract 001

Status: proposed implementation contract; not yet adopted as a production integration.
Branch: `review-history-identity-read-evidence-001`.

## Purpose

Prevent a JavaScript graph outcome and a Lean theorem from being compared as though they concern the same graph when the vertex set, parent edges, identity mapping, or source coverage differ.

## Required evidence layers

1. **Read identity** — identify the exact input read or immutable snapshot used by the adapter. A fixture derived from a read is not a live read.
2. **Record identity integrity** — establish that every included record ID is well-formed and unique. Any numeric Lean vertex encoding must have a documented, injective mapping to these record IDs.
3. **Vertex-set binding** — retain the exact declared vertex set derived from the same input records passed to the JavaScript analyzer. The Lean `ParentGraphInput.vertices` must encode that same set, not a separately hand-built approximation.
4. **Edge-set binding** — derive each parent edge from the same records and the same exact parent-reference rules used by the runtime. Every non-empty valid parent reference must either map to one included parent vertex or produce explicit unresolved-reference evidence; it must not silently disappear.
5. **Internal well-formedness** — check that every edge endpoint belongs to the declared vertex set. This is what `parentGraphInputWellFormed` can check for the supplied graph.
6. **Source completeness** — separately establish that the input read includes all records in the intended scope and that the extracted edge list includes every applicable parent link. Internal graph well-formedness does not prove either fact.
7. **Outcome evidence** — a cycle result requires a checked witness against the bound edge list. A no-cycle result requires valid identity evidence, faithful edge extraction, source-completeness evidence, and a Lean proof of acyclicity for that exact edge list. Otherwise the comparison remains `indeterminate`.

## Binding record shape

An eventual adapter certificate should carry, at minimum:

- source/read or snapshot identifier and its immutable digest;
- ordered or canonically serialized source record IDs;
- explicit mapping from source record IDs to Lean vertex IDs;
- the complete declared vertex set and derived parent edge list;
- malformed, duplicate, and unresolved-reference findings;
- evidence that the source scope is complete;
- a digest of the exact graph input supplied to Lean;
- the runtime outcome and, where applicable, a cycle witness;
- the Lean result/proof reference for that same graph digest.

A digest establishes byte-level identity of the certified payload; it does not, by itself, prove that the source read was complete or truthful.

## Current implementation boundary

- The JavaScript resolver is a runtime reference implementation and does not establish source exhaustiveness.
- `ParentGraphInput` checks consistency relative to the vertex list it receives.
- `ParentGraphAcyclic` is a proof-carrying specification, not an executable exhaustive cycle finder.
- `ResolverGraphOutcomeMapping` is conditional; it does not prove JavaScript/Lean implementation equivalence.
- The Airtable pilot snapshot is static and must not be represented as a live connection.
- No production adapter, source-completeness verifier, or end-to-end correspondence proof is claimed by this document.

## Acceptance checks before claiming correspondence

- [ ] Same source read/snapshot is named by the runtime and Lean input.
- [ ] Source record IDs map injectively to Lean vertices.
- [ ] Vertex set equality is checked, not assumed.
- [ ] Every in-scope parent reference is represented by an edge or an explicit error.
- [ ] Edge-list equality/faithfulness is checked against the source records.
- [ ] Source completeness is independently justified.
- [ ] Cycle witnesses are checked against the bound edge list.
- [ ] No-cycle is accepted only with an acyclicity proof for that exact edge list.
- [ ] Tests cover missing targets, duplicate/malformed IDs, disconnected cycles, and incomplete source scope.

Until all applicable checks pass, report the result as a conditional reference-model comparison, not JS/Lean equivalence.
