# Post-Connection Report Contract — Adaptive Filesystem

Status: proposed contract; not yet implemented or test-verified.

## Why this exists

The recovery checkpoint schema previously listed state categories, but did not make generation of a post-connection report an explicit required output. A schema that records state is not, by itself, a workflow that requires every output to be generated.

Inspection of `tests/adaptive_filesystem/test_recovery_roundtrip.py` confirms that the synthetic harness generates a particle report before activation and then constructs the second FIRA-associated wave from that report. It has no separate post-connection report function or required post-connection report assertion.

The inspected Lean model (`YggdrasilLean/ParticleWave.lean`) defines expandable particle/wave containers and preservation theorems; it does not currently implement the recovery/report workflow. Therefore this is a design-level gap observed in the inspected files. The exact historical cause of the earlier schema-first omission remains unknown.

## Required six-part post-connection report

After activation forms the second FIRA-associated wave, generate a report containing exactly these six sections:

1. **Connection identity** — source wave, particle-state/checkpoint identity, and resulting second-wave identity; use stable IDs when available.
2. **Connection result** — connected, partially connected, failed, or unknown; never infer success from an attempted connection.
3. **Preserved state** — records, relationships, provenance, status, and intended changes carried through.
4. **Differences and changes** — compare expected and observed state; distinguish intended, applied, and verified changes.
5. **Unresolved issues** — blockers, missing evidence, unavailable sources, and uncertainties, explicitly labelled.
6. **Post-connection report generation and verification** — report identity, generation point (after connection), inputs used, checks performed, outcome, and evidence/links for read-back.

The sixth section is not merely metadata: its presence and content must be a required acceptance condition. If report generation cannot run, emit an explicit failure/unknown result rather than silently omitting the section.

## Keep the two report stages distinct

- **Particle report:** generated from the particle form's TERA state by AIRA-of-TERA, including the leaf-like parts of the roots. It feeds construction of the second FIRA-associated wave.
- **Post-connection report:** generated after that second wave has been formed; it records the connection outcome and verifies the resulting state.

These are separate lifecycle points and must not be conflated.

## Schema-first failure investigation

Likely design-level failure mode supported by the inspected artifacts:
- State fields were enumerated, but the post-connection report was not represented as a mandatory workflow output.
- No acceptance check required all six report sections to exist.
- No executable test asserted that report generation occurs *after* activation and includes a connection outcome.

Not established:
- The precise historical reason the original schema did not prompt the sixth component.
- Whether an earlier schema or implementation had additional fields not present in the inspected files.
- Any production runtime integration; the current Python harness explicitly describes itself as synthetic.

## Minimum acceptance tests for the next implementation step

1. A successful activation yields a post-connection report with exactly six required sections.
2. The report is generated after the second wave exists, not before.
3. Missing or failed connection is reported as failed/unknown, never successful by default.
4. Preserved state and differences are tied to the same source/checkpoint/result identities.
5. Planned changes remain distinct from applied and verified changes.
6. If generation or read-back fails, the report marks that failure and does not claim verification.
7. The particle report remains separate from the post-connection report.

## Scope boundary

This document is a contract proposal, not evidence that a production runtime or report generator exists. Implementation and CI results must be recorded separately after the contract is wired to executable code.
