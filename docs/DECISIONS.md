# Decisions and assumptions

- Purpose: establish a durable, automatically buildable Lean project.
- Repository visibility: public, confirmed by the user on 2026-10-23.
- Position order: left operand, operator, right operand.
- Current wave-label hypothesis: TERA, FIRA, WATA.
- Current particle-label hypothesis: TERA, WATA, FIRA.
- Label transformation: swap FIRA and WATA; fix TERA and AIRA.
- Operand transformation: exchange left/right operands while preserving operator identity.
- A slot's category label is distinct from its semantic identity.
- Status: draft; compiler verification pending.

## Open questions

1. Confirm label assignments against primary source records.
2. State the valid domain and assumptions for each transformation.
3. Review every theorem for intended mathematical meaning after it compiles.
4. Preserve unresolved alternatives rather than silently selecting one.


## 2026-11-15 — Wingbeat / Lower WATA (proposed formal foundation)

- Adopted conceptually in discussion: Nine Characters' progression is Hunting → Dancing → Flying.
- The Wingbeat alternates between microcosm (within a system) and macrocosm (beyond that system, in relation to other systems).
- Both Particle–Wave–Particle and the Hero's Journey can occur at either scale.
- Particle–Wave–Particle is the structural cycle; the Hero's Journey describes the transformational journey. The boon is the emergent property carried for reintegration.
- First Lean representation is on branch wingbeat-foundation-001. The typed Lower WATA vocabulary is a draft, not yet a parser or executable runtime.
- Preserve axis distinction: TERA is the reference; FIRA is horizontal; WATA is vertical. Wilderness as thought/conception is WATA; hunting movement across wilderness is horizontal FIRA.
- Verification and implementation status must be updated only after GitHub Actions and later runtime tests provide evidence.


## 2026-11-15 — Lower WATA interpreter foundation (implementation draft)

- Added a pure Lean interpreter that validates typed instructions against explicit scale, journey stage, developmental capability and an operator-approval flag.
- Added state transitions for the Hero's Journey, Particle–Wave–Particle phase advancement, capability progression (Hunting → Dancing → Flying), and Wingbeat scale alternation.
- Cross-boundary operation is currently simulation-only and requires macrocosmic scope, Flying capability and an explicit approval flag.
- Boon integration requires a recorded boon, the return stage and an explicit approval flag.
- Every attempted instruction appends an in-memory accepted/rejected audit entry, including the supplied approval flag. This is not authenticated identity, durable storage, or a complete authorization system.
- Added theorem-level regression examples for denied capability promotion, successful Hero's Journey reintegration, Wingbeat alternation and cross-boundary authorization gating.
- Status: committed draft; compiler/CI verification pending. No external action, persistent runtime, scheduler or autonomous agent is implemented.
