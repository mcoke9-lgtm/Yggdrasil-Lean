# Yggdrasil Lean

A Lean 4 project for formalizing the TERA/FIRA/WATA/AIRA representation model.

**Status: initial draft; compiler verification pending.** Do not treat theorems as compiler-confirmed until GitHub Actions reports a successful build.

## Structure

- `YggdrasilLean/ParticleWave.lean` — draft definitions and candidate theorems.
- `YggdrasilLean.lean` — library entry point.
- `lean-toolchain` — pinned Lean version.
- `lakefile.toml` — Lake project configuration.
- `.github/workflows/lean.yml` — automated build.
- `docs/DECISIONS.md` — current assumptions and open questions.
- `docs/VERIFICATION.md` — build evidence log.

## Build

With Lean 4 and Lake installed, run:

```sh
lake build
```

## Current modeling hypothesis

Position order: left operand, operator, right operand.
- Wave labels: `(TERA, FIRA, WATA)`
- Particle labels: `(TERA, WATA, FIRA)`

The draft separates a slot's category label from the semantic identity carried at that position. These assignments are a current hypothesis to check against source records, not a claim about every version of the wider framework.
