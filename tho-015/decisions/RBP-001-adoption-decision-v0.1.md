# RBP-001 Adoption Decision

**Decision ID:** RBP-001-ADOPT-001
**Version:** 0.1
**Date:** 2026-11-11
**Status:** Adopted
**Decision authority:** User

## Decision

Adopt RBP-001's repository-boundary direction and governing principles:

1. Formal models remain separately identifiable from executable implementation.
2. YIM-001 implementation source and executable verification are to have an independent repository boundary.
3. Operational memory, provenance, checkpoints, recovery material, and exports are to remain outside source repositories.
4. Source, evidence, and operational data must remain independently identifiable, reproducible, and recoverable.
5. GitHub may host or mirror source, but must not be a runtime prerequisite for the Living Tree.
6. Repository boundaries do not silently transfer canonical authority.
7. Preserve source history and evidence; do not delete or rewrite original source as part of a future migration.
8. No paid hosting, cloud provisioning, or hardware purchase is implied.

## Scope and limits

This is adoption of the architectural boundary and principles, not authorization to execute a migration.

The following remain undecided and require separate decisions:
- whether the independent YIM-001 repository is local-only, self-hosted, or mirrored to GitHub;
- repository owner, hosting location, canonical remote, and backup control;
- final path-level inclusion/exclusion and treatment of historical probes;
- verified history-extraction method;
- operational data storage, access control, and recovery topology;
- timing and criteria for any canonical-authority transfer.

## Explicit non-authorizations

This decision does not authorize:
- creation of a new repository or remote;
- extraction, copying, pushing, moving, rewriting, or deleting source files or branches;
- migration of live memory or provenance data;
- changes to YIM-001 code, specifications, tests, or adopted decisions;
- paid services, cloud resources, or hardware purchases;
- transfer of canonical authority.

The existing source repository and branch remain intact. The migration manifest remains a draft plan, not an execution record.

## Basis and provenance

- Adopted document: tho-015/proposals/RBP-001-repository-boundary-proposal-v0.1.md
- Draft migration manifest: tho-015/proposals/RBP-001-migration-manifest-v0.1.md
- Source checkpoint used to prepare the proposal: mcoke9-lgtm/Yggdrasil-Lean, branch tho-015/probe1-ci-bootstrap, commit 6ce49b472cc13bc7069a4adcf5a2729e333b2b04
- User authorization: affirmative Y following the explicit offer to decide whether to adopt RBP-001.

## Change log

- v0.1 — Initial adoption decision. Architectural boundary adopted; implementation and migration remain unauthorized.