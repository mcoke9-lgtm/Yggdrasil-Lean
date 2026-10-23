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
