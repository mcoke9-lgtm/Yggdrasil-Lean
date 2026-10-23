# Particle–Wave Semantic Review 001

- Date: 2026-10-23
- Scope: `YggdrasilLean/ParticleWave.lean`
- Status: bounded semantic review; conceptual source mapping remains unresolved
- Build evidence: `lake build` passed on commit `e097d568ca56ce3cb0d7de759f27dca90ae3b30a`; follow-up verification-record commit also passed in [run 40036844149](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40036844149).

## 1. Locate primary source

The current Library search returned secondary summaries describing the working assignments, but did not recover the original construction/diagram that established them. Therefore this review treats the mapping as a recorded working hypothesis, not a source-verified canonical definition.

Recorded working hypothesis:
- Wave labels: `(TERA, FIRA, WATA)`
- Particle labels: `(TERA, WATA, FIRA)`
- Position order in the Lean model: left operand, operator, right operand.
- `Category` labels and `SemanticIdentity` values are separate fields.

## 2. Represent the exact formal object

The module defines:
- a finite `Category` type with TERA, FIRA, WATA, AIRA;
- a `SemanticIdentity` type for left value, operator value, right value, and an extensible `other Nat`;
- `SlotValue`, pairing one category label with one semantic identity;
- `Representation`, containing left, operator, and right slots.

This is an explicit typed representation. It does not encode a mathematical expression's evaluation, its type/domain, or the semantic meaning of TERA/FIRA/WATA themselves.

## 3. Compare claims and assumptions

### Claims that compile

The successful build establishes that Lean accepts the proofs of:
1. swapping a slot's category label preserves its semantic identity;
2. the FIRA/WATA label swap is involutive;
3. applying the label swap twice restores any representation;
4. exchanging operands twice restores any representation;
5. label swapping commutes with operand exchange;
6. the concrete wave and particle examples have their declared labels;
7. swapping the example's wave labels yields the particle labels, and vice versa;
8. the exchanged wave example does not satisfy the exact wave-label predicate.

### Domain assumptions / boundaries

- The transformations are total functions over all `Representation` values; no validity predicate restricts inputs.
- The labels are metadata in this model, not proof that a value truly has the corresponding framework role.
- Operand exchange is modeled as swapping left and right slots while retaining the operator slot. It does not define whether the represented operator is commutative or whether exchanging operands preserves expression meaning.
- `exchanged_wave_is_not_wave` proves a narrow label-pattern fact. It does not establish that an operand-exchanged expression is mathematically invalid or semantically different in every domain.
- The model has no explicit distinction between lower and upper TERA/FIRA. The known lower/upper role assignments must not be inferred from this module.

## 4. Transformations and invariants

- **Label swap:** FIRA ↔ WATA; TERA and AIRA fixed; semantic identities unchanged.
- **Operand exchange:** left and right slots exchange; operator slot remains in place.
- **Verified algebraic properties:** each transformation is an involution; they commute.
- **Not yet modeled:** typed expression domains, valid operand/operator combinations, evaluation semantics, domain-specific commutativity, or source-derived lower/upper roles.

## 5. Preserve provenance and unresolved questions

Open questions:
1. Can the original source or diagram establishing the particle/wave assignments be recovered?
2. Are labels assigned by position, by semantic role, or by a rule that depends on context?
3. What are the intended valid domains for each transformation?
4. Does operand exchange represent a structural reordering only, or is it intended to preserve a value under a commutativity assumption?
5. How do lower/upper TERA and FIRA fit into the typed representation, if at all?

Do not silently resolve these questions from the current theorem names or successful compilation.

## 6. Verify and next action

The Lean build is verified by GitHub Actions, but this is a bounded formalization audit, not a complete mathematical or source audit. The next source-grounded step is to recover the original construction or diagram. Once available, compare its literal assignments and transformation rules against this model before adding domain-specific predicates or stronger semantic theorems.
