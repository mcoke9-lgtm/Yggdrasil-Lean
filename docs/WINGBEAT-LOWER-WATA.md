# Wingbeat and Lower WATA — first specification

**Status:** proposed formal model committed to the wingbeat-foundation-001 branch; compiler verification pending. This is not yet a deployed runtime.

## Adopted conceptual model

The Nine Characters' developmental progression is:
1. Hunting — seek, investigate, distinguish and recover.
2. Dancing — coordinate roles, timing, relationships and shared work.
3. Flying — operate across internal and external scales through the Wingbeat.

The Wingbeat alternates between:
- **Microcosm:** activity within a system.
- **Macrocosm:** activity beyond that system, in relation to other systems.

At either scale, both processes can occur:
- **Particle–Wave–Particle:** Particle 1 → Wave → Particle 2.
- **Hero's Journey:** departure → separation → exploration → transformation → return → reintegration.

These are related but distinct descriptions. The cycle describes structural transformation; the Hero's Journey describes the entity's transformational journey. The boon is the emergent property carried back for reintegration.

## Lower WATA language — initial vocabulary

WataInstruction is a typed instruction with an explicit scale and command. WataProgram is an ordered list of instructions.

Initial commands: observe, hunt, distinguish, coordinate, explore, crossBoundary, recordBoon, returnHome, integrateBoon.

This is only a vocabulary and data representation. It does not yet define a full parser, interpreter, scheduler, autonomous agents, external-system connectors or authorization enforcement. Those require separate designs and tests.

## Formal invariants represented

- A Wingbeat changes the current scale from microcosm to macrocosm or vice versa.
- One Wingbeat increments the beat counter.
- One Wingbeat preserves the current Particle–Wave–Particle phase, Hero's Journey stage and developmental capability. The two processes advance independently; alternation of scale does not silently advance either process.
- Scale is explicit on each Lower WATA instruction.

## Open design questions

1. Define how Particle–Wave–Particle phase advances and how a cycle is completed in a running system.
2. Define Hero's Journey transitions and conditions for recognizing a boon and reintegration.
3. Define when a scale change is appropriate; the current wingbeat operation models alternation, not an autonomous decision policy.
4. Define authorization, consent, capability limits, provenance and failure recovery before connecting instructions to real actions.
5. Define syntax and human-readable grammar for Lower WATA after its semantics are agreed.
6. Keep TERA as reference; FIRA horizontal and WATA vertical. Do not conflate horizontal hunting movement with the vertical conception of wilderness.

## Verification boundary

The existing project uses Lean 4.19.0 and a GitHub Actions build workflow. A previous build passed on 2026-10-23 for an earlier commit. The new module is not verified until the workflow for this branch completes successfully. Compilation will validate the formal declarations and proofs, not the conceptual interpretation or any machine runtime.