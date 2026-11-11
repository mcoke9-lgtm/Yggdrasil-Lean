# RBP-001 Hosting Direction Decision

**Decision ID:** RBP-001-ADOPT-002
**Version:** 0.1
**Date:** 2026-11-11
**Status:** Adopted
**Decision authority:** User

## Decision

Adopt a **local-first, GitHub-optional** hosting direction for the independent YIM-001 repository.

1. YIM-001 is to be an ordinary Git repository that can be cloned, built, tested, and used without GitHub-specific runtime services.
2. A local working copy is the primary operational starting point.
3. GitHub may be used as an optional mirror, collaboration interface, or CI host; it is not a runtime dependency.
4. Recovery and backup must be considered separately. A Git remote alone is not a complete recovery strategy, especially for operational memory and provenance data.
5. Any history-preserving extraction begins with a dry run and explicit verification of source history, file hashes, evidence references, and independent tests.
6. Keep the existing source repository and branch intact during migration. Canonical-authority transfer remains a separate decision.
7. No paid hosting, cloud provisioning, or hardware purchase is authorized or implied.

## Scope and non-authorizations

This decision settles the hosting direction only. It does not authorize:
- creating a new repository or remote;
- extracting, copying, pushing, moving, rewriting, or deleting source files or branches;
- migrating live memory or provenance data;
- selecting a self-hosted server, cloud provider, backup vendor, or hardware;
- transferring canonical authority.

Repository owner, local filesystem location, remote/mirror configuration, backup topology, access control, and recovery procedures remain to be specified.

## Basis and provenance

- Parent decision: RBP-001-ADOPT-001.
- Adopted boundary proposal: tho-015/proposals/RBP-001-repository-boundary-proposal-v0.1.md.
- Draft migration manifest: tho-015/proposals/RBP-001-migration-manifest-v0.1.md.
- User authorization: affirmative Y following the explicit local-first/GitHub-optional recommendation.

## Change log

- v0.1 — Adopted local-first, GitHub-optional hosting direction. No repository or migration operation performed.