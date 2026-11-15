# Wingbeat and Lower WATA — foundation and interpreter

**Status:** conceptual progression adopted in discussion. Formal model and bounded pure interpreter committed on branch wingbeat-foundation-001. Compiler verification is pending. No deployed runtime or external action capability is claimed.

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

## Lower WATA typed vocabulary

Each WataInstruction explicitly names its scale and command. The interpreter accepts an ordered program paired with one explicit operator permit per instruction.

Current command vocabulary:

- Observation and discovery: observe, hunt, distinguish, explore.
- Coordination and flight: coordinate, wingbeat, crossBoundary.
- Process control: advanceCycle, depart, separate.
- Character development: learnDance, learnFlight.
- Boon and return: recordBoon, returnHome, integrateBoon.

## Interpreter behavior

LowerWataInterpreter is a **pure state interpreter**:

- It checks that an instruction's declared scale matches the current state.
- It checks capability requirements for coordination, flight and cross-boundary operations.
- Learning to dance and learning to fly require explicit operator approval.
- Crossing a system boundary requires flying capability, macrocosmic scope and explicit operator approval.
- Boon integration requires a recorded boon, the Hero's Journey return stage and explicit operator approval.
- Invalid instructions do not change the model state or boon flag.
- Every attempted instruction appends an accepted/rejected entry to an in-memory audit list, including whether the supplied operator-approval flag was true or false. This flag is not an authenticated operator identity or a complete authorization proof.
- Wingbeat alternates scope and increments the beat counter; it does not implicitly advance the Particle–Wave–Particle phase or Hero's Journey stage.

The interpreter does not perform IO, persist state, contact external systems, schedule tasks, or operate autonomous agents. crossBoundary is currently a validated simulated instruction with no external effect. The audit list is in-memory and is not yet a durable append-only archive.

## Regression examples added

The test module includes theorem-level examples for:

1. Rejected dance promotion without approval leaves capability unchanged and records an audit entry.
2. A six-step Hero's Journey reaches reintegration and records all six attempts.
3. A flying-capable system can switch to macrocosmic scope and increment its beat.
4. Cross-boundary instructions are gated by explicit operator approval.

These examples must compile under the repository's Lean 4.19.0 CI before being called verified.

## Open design questions

1. Define a durable event format and persistence/recovery protocol for audit entries.
2. Define the actual human-readable Lower WATA grammar and parser; current programs are typed Lean values, not parsed text.
3. Refine the authorization model: identify who may approve which action, how approvals are scoped, expire and revoked, and how authorization evidence is retained.
4. Define when the Wingbeat should alternate scope; current interpreter exposes a command but does not autonomously decide when to fly.
5. Define connectors only after the pure interpreter, policy and durable audit are verified. External actions must remain disabled until separately authorized and tested.
6. Keep TERA as the reference; FIRA is horizontal and WATA vertical. Wilderness as thought/conception is WATA; hunting movement across wilderness is horizontal FIRA. Do not conflate the axes.

## Verification boundary

The repository uses Lean 4.19.0 and a GitHub Actions build workflow. Earlier build evidence applies only to earlier revisions. This interpreter and its regression examples are not compiler-verified until the CI result for this branch is observed. Compilation will validate the formal declarations and proofs, not the conceptual interpretation or a deployed machine runtime.
