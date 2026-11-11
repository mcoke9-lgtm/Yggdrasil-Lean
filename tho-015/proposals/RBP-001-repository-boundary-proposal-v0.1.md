# RBP-001 — Repository Boundary Proposal

**Document:** RBP-001  
**Version:** 0.1  
**Status:** Adopted as a repository-boundary decision; implementation and migration not authorized  
**Prepared:** 2026-11-11  
**Source checkpoint:** `mcoke9-lgtm/Yggdrasil-Lean`, branch `tho-015/probe1-ci-bootstrap`, commit `6ce49b472cc13bc7069a4adcf5a2729e333b2b04`

## 1. Purpose

Define a boundary between formal models, executable implementation, and operational data so that the Living Tree can be developed, reproduced, and operated without depending on one hosting provider or one source repository.

This proposal does not authorize repository creation, source extraction, file movement, deletion, or a change of canonical authority.

## 2. Adopted architectural boundary

The separation described below is adopted as the governing repository-boundary direction. Specific repository creation, hosting, canonical-remote selection, path-level extraction, operational storage, and migration execution remain subject to separate decisions and authorization.



### 2.1 Formal-model repository

Retain the existing `Yggdrasil-Lean` project as the home for Lean 4 formal models, proofs, Lean-specific build configuration, and documentation directly governing those models.

Expected content includes:
- `YggdrasilLean/` and `YggdrasilLean.lean`;
- `lakefile.toml`, `lake-manifest.json`, and `lean-toolchain`;
- Lean-specific verification workflows and model documentation.

### 2.2 Independent implementation repository

Establish a separate Git repository for YIM-001 implementation source and executable verification. Its proposed name is `YIM-001`; repository owner, hosting location, and canonical remote remain undecided.

Expected content includes:
- `yim-001/src/yim001/`;
- `yim-001/tests/`;
- `yim-001/scripts/`;
- implementation requirements and packaging configuration;
- implementation-facing CLI, architecture, and operational documentation;
- specifications and decisions necessary to implement and verify YIM-001.

The repository must be cloneable and usable without GitHub-specific runtime services. GitHub may remain an optional mirror, CI host, or collaboration interface.

### 2.3 Operational data area

Keep actual memory snapshots, provenance ledgers, checkpoints, recovery material, and exports outside both source repositories. Their storage authority, backup topology, access control, and recovery procedure require separate specification.

A proposed logical layout is:

```text
Living-Tree-Data/
├── memory/
├── provenance/
├── checkpoints/
├── recovery/
└── exports/
```

This is a logical proposal, not a decision to use a particular device, cloud, or filesystem.

## 3. Boundary principles

1. **Independent identity:** each repository and each operational data authority has an explicit identity.
2. **Reproducibility:** the implementation repository must independently build, test, and package YIM-001.
3. **History preservation:** extraction must preserve or explicitly map original commit ancestry, file identity, and provenance.
4. **Evidence preservation:** test runs, artifacts, hashes, and source commits remain traceable.
5. **No silent authority transfer:** moving files does not by itself make a new repository canonical.
6. **No premature deletion:** the source material remains intact until extraction and verification are complete and separately authorized.
7. **Portability:** GitHub may host or mirror source but must not be a runtime prerequisite.
8. **Operational separation:** live memory and provenance data are not casually committed to source repositories.
9. **No implied infrastructure purchase:** no paid hosting, cloud provisioning, or hardware purchase is authorized by this proposal.

## 4. Migration method

Prefer history-preserving extraction (for example, a filtered Git history or subtree-based extraction) over copying only the current files. The exact method must be selected after a dry run demonstrates that the required paths, commit ancestry, and evidence references can be retained.

A migration manifest must record:
- source repository, branch, and immutable checkpoint commit;
- source paths and their destination paths;
- relevant source commits and blob hashes;
- extraction method and resulting destination commit(s);
- CI/test evidence and package hashes;
- exceptions, unavailable history, and unresolved provenance gaps.

Do not rewrite the source branch as part of extraction.

## 5. Migration phases (proposed; not authorized)

1. Freeze and verify the source checkpoint.
2. Finalize the source-to-destination path map.
3. Perform a history-preserving extraction into a separately created repository.
4. Add independent build, test, and packaging workflows.
5. Reproduce the current verified YIM-001 test checkpoint and compare package/source hashes.
6. Verify documentation, decision, and evidence references.
7. Present the results for a separate canonical-authority decision.

No phase after preparation is authorized by this proposal alone.

## 6. Acceptance criteria before canonical transfer

- Required source paths are present in the destination.
- Relevant Git history is preserved or each exception is documented.
- The destination builds and tests independently.
- The current linked-create implementation and its known limitations are accurately represented.
- Evidence references resolve to the correct source commits and CI runs.
- A migration manifest records source and destination identities and hashes.
- The original source remains available and unchanged.
- The user explicitly adopts the new repository boundary and separately authorizes any canonical-authority transfer.

## 7. Current implementation checkpoint and limitations

The inspected source branch reports a successful Core Memory System CI run #43025937816 (96 tests) and Lean build #43025937848. These are checkpoint evidence, not proof of full linked-create conformance.

Known limitations remain:
- linked-create conformance is partial;
- the complete persistence-boundary fault-injection matrix remains outstanding;
- duplicate, contradictory, and partial-state cases remain outstanding;
- recovery is not implemented or authorized;
- cross-file atomicity is not claimed;
- the direct-conflict versus malformed-history precedence issue remains unresolved.

The migration must preserve these qualifications and must not describe the implementation as fully conformant.

## 8. Decisions still open

- Whether the independent repository is initially local-only, self-hosted, or mirrored to GitHub.
- Who/what controls the canonical remote and backups.
- Which exact documentation and evidence paths are included in the implementation repository.
- Which history-extraction method is verified to preserve the required provenance.
- Whether and when canonical authority transfers from the existing branch/repository.

## 9. Change control

The repository-boundary separation and principles in Sections 2–3 are adopted by the user's decision recorded in tho-015/decisions/RBP-001-adoption-decision-v0.1.md. The path mapping and migration phases remain proposed planning material. This adoption does not amend MRP-001 Revision 4, YIM-001-WGS, the YIM-001 linked-create contract, or any adopted implementation decision. It does not authorize code changes, repository creation, source extraction, data migration, canonical-authority transfer, or deletion.
