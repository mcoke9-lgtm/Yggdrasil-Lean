# RBP-001 — Draft Migration Manifest

**Document:** RBP-001-MANIFEST  
**Version:** 0.1  
**Status:** Draft mapping; no migration performed  
**Prepared:** 2026-11-11

## 1. Immutable source checkpoint

| Field | Value |
|---|---|
| Source repository | `mcoke9-lgtm/Yggdrasil-Lean` |
| Source branch | `tho-015/probe1-ci-bootstrap` |
| Source commit | `6ce49b472cc13bc7069a4adcf5a2729e333b2b04` |
| Formal-model default branch checkpoint inspected | `main` at `4097f38839cd4422e6038f00979232d627db07ba` |
| Source tree at THO-015 checkpoint | 114 entries; not truncated |
| Migration performed | No |
| Destination repository | Not created / not selected |

The THO-015 branch is the relevant source checkpoint for the current YIM-001 implementation. The `main` branch is the existing Lean-focused project baseline; it is not a substitute for the THO-015 implementation checkpoint.

## 2. Proposed path mapping

| Source path at checkpoint | Proposed destination | Treatment |
|---|---|---|
| `YggdrasilLean/` | `Yggdrasil-Lean/YggdrasilLean/` | Retain in formal-model repository |
| `YggdrasilLean.lean` | `Yggdrasil-Lean/YggdrasilLean.lean` | Retain in formal-model repository |
| `lakefile.toml`, `lake-manifest.json`, `lean-toolchain` | Formal-model repository root | Retain |
| `.github/workflows/lean.yml` | Formal-model repository workflow | Retain |
| Lean-specific `docs/*.md` | Formal-model repository `docs/` | Retain after content review |
| `yim-001/src/yim001/` | `YIM-001/src/yim001/` | Extract with history |
| `yim-001/tests/` | `YIM-001/tests/` | Extract with history |
| `yim-001/scripts/` | `YIM-001/scripts/` | Extract with history |
| `yim-001/requirements.txt` | `YIM-001/requirements.txt` | Extract |
| `yim-001/CLI.md` | `YIM-001/docs/CLI.md` | Extract; preserve source reference |
| `yim-001/ARCHITECTURE.md` | `YIM-001/docs/architecture/ARCHITECTURE.md` | Extract; review scope |
| `yim-001/PROVENANCE.md` | `YIM-001/docs/PROVENANCE.md` | Extract |
| `yim-001/RECONCILIATION-DESIGN.md` | `YIM-001/docs/RECONCILIATION-DESIGN.md` | Extract |
| `yim-001/WRITE-GATING-SPEC.md` | `YIM-001/docs/specifications/WRITE-GATING-SPEC.md` | Preserve adopted status and decision references |
| `yim-001/README.md`, `yim-001/STATUS.md` | YIM-001 repository root | Reconcile paths and branch/status claims during extraction |
| `yim-001/HARDENING-REPORT.md`, `yim-001/TEST-REPORT.md` | `YIM-001/docs/` | Preserve as historical reports; refresh only through separate versioned updates |
| `yim-001/SHA256SUMS.json` | YIM-001 release/evidence area | Recompute only for a new package; retain original as historical evidence |
| `tho-015/decisions/` | YIM-001 `docs/decisions/` plus retained project-history archive | Extract relevant decisions; preserve original locations/commit references |
| `tho-015/evidence/` | YIM-001 `evidence/` plus retained project-history archive | Preserve verbatim and retain source commit/CI references |
| `tho-015/proposals/` | Project-history archive; selected implementation proposals may be copied with provenance | Do not treat proposals as adopted specifications |
| `tho-015/reconciliation/` | Project-history archive | Preserve reconciliation history and addenda |
| `tho-015/probe*.py` | Project-history archive or clearly marked historical test harnesses | Do not merge into production package without review |
| `.github/workflows/yim001-tests.yml` | YIM-001 CI workflow | Recreate/adapt after independent build verification |
| `.github/workflows/tho015-probe1.yml` | Project-history archive or migration validation workflow | Review before retaining |
| Actual memory snapshots/event ledgers | Outside both source repositories | Not present in this source-tree mapping; do not migrate as source files |

This is a proposed mapping, not an instruction to move files. Any path not explicitly listed must be classified before extraction.

## 3. Evidence anchors to preserve

| Evidence item | Immutable reference |
|---|---|
| THO-015 source checkpoint | `6ce49b472cc13bc7069a4adcf5a2729e333b2b04` |
| Linked-create source | `yim-001/src/yim001/linked_create.py`, blob `e1ca1cec8b212fc0c30a1de13ef0b7c79a6b70f6` |
| Linked-create evidence | `tho-015/evidence/YIM-001-linked-create-persistence-evidence-v0.1.md`, blob `b0f513dc5d0ddc09bd363ccb8d022c15ddb6ded1` |
| Linked-create conformance plan | `tho-015/proposals/YIM-001-opt-in-linked-create-conformance-test-plan-v0.1.md`, blob `93af87cf41047a546cecb6759c4b16efb98f6b86` |
| YIM-001 status | `yim-001/STATUS.md`, blob `592aaf6d60606138f4cbc5cf3a6f999dea3bdccc` |
| Write-gating specification | `yim-001/WRITE-GATING-SPEC.md`, blob `2cf505cfdb4af5f985a9b22ed5b0118938f876b3` |
| Linked-create CI | Core Memory System run [#43025937816](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025937816), 96 tests passed |
| Lean CI | run [#43025937848](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43025937848), passed |

Blob hashes identify file content at the inspected checkpoint. Commit references identify repository history. They are distinct kinds of evidence and must not be conflated.

## 4. Extraction and verification record

| Field | Status |
|---|---|
| Extraction method | Not selected |
| Dry-run extraction | Not performed |
| Destination repository | Not created |
| Destination commit | Not applicable |
| Independent test run | Not performed |
| Destination package hash | Not applicable |
| Path/hash comparison | Not performed |
| Canonical authority transfer | Not authorized |

## 5. Required migration checks

Before declaring extraction verified:
1. Record exact source commit and tree hash.
2. Verify all mapped paths and enumerate unclassified paths.
3. Demonstrate history preservation for implementation source and relevant tests.
4. Compare extracted file contents against source blob hashes.
5. Build and run tests in a clean destination checkout.
6. Rebuild the portable package and record its SHA-256 separately from the historical package.
7. Verify decision, specification, evidence, and CI references.
8. Record all exclusions, transformations, and unresolved provenance gaps.
9. Keep the source repository and branch unchanged.
10. Request a separate decision before changing canonical authority.

## 6. Non-authorizations

This manifest does not authorize:
- creating or paying for a new hosting service;
- creating a GitHub repository;
- pushing source to a new remote;
- moving, deleting, or rewriting existing files or branches;
- moving live memory or provenance data;
- changing YIM-001 specifications or implementation behavior;
- declaring a new canonical source of truth.

## 7. Change log

- v0.1 — Initial proposed path map and evidence anchors prepared from the read-only THO-015 source checkpoint. No migration performed.
