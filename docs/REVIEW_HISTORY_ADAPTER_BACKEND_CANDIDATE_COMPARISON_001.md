# Review History Adapter Backend Candidate Comparison 001

**Status:** conditional comparison; no backend selected  
**Branch:** `review-history-identity-read-evidence-001`  
**Inputs:** deployment requirements register 001; adapter contract 001; integration test specification 001

## Decision boundary

The current project records do not establish whether history is local to one process, shared by multiple local processes, or shared across machines. This is a blocking requirement, not permission to assume a deployment. Therefore, the comparison below is conditional and does not rank a winner.

## Candidate fit by deployment shape

| Candidate family | One local process | Multiple local processes | Multiple machines | Principal gates / risks |
|---|---|---|---|---|
| In-memory state | Only for ephemeral sessions; fails restart durability | Not shared by default | Not shared by default | Does not meet durable history after restart; unsuitable if persistence is required. |
| Local append-only files | Potentially feasible with explicit crash-safe writes and recovery | Requires proven cross-process locking/atomicity and repair rules | Not suitable as shared storage by default; network filesystem semantics would need evidence | Partial writes, concurrent append, torn/corrupt records, indexing, pagination, recovery. |
| Embedded transactional database (e.g., SQLite) | Often a plausible candidate | Potentially feasible on a supported local filesystem with tested transaction and locking behavior | Not a default multi-machine shared database; avoid assuming a database file on a network share is safe | Atomic compare-and-append, durability settings, backup, migration, reader snapshots, busy/failure handling. |
| Client/server database | Feasible but may add unnecessary operational dependencies | Plausible when multiple local clients need one authoritative history | Plausible when machines need shared history | Service availability, authentication/authorization, transactions, backups, migration, complete-read semantics, network failure and uncertain commit handling. |

These are general engineering fit observations, not test results for this repository or guarantees about any particular configuration.

## Selection gates

Before scoring candidates, resolve:

- **DEP-1:** where the adapter runs.
- **DEP-2:** which processes/machines must share the same history and who may write.
- **DEP-3:** what must survive process exit, restart, and power loss.
- **DEP-7:** which component is trusted to attest lookup scope/completeness and validate identity matches.
- **DEP-8:** whether network or external service dependencies are acceptable.

Then score each viable candidate against the same evidence checklist:

1. Atomic append plus expected-position enforcement.
2. Safe retry after an uncertain commit result.
3. Durable ordered history after restart.
4. Complete identity lookup or an explicit incomplete/unresolved result.
5. Pagination consistency and snapshot semantics.
6. Recovery, backup, portability, and schema migration.
7. Distinct reporting of corruption, unsupported schema, incomplete reads, and unavailability.
8. Execution of integration tests IT-1 through IT-6 against the real adapter.

Unknowns must be marked **unknown**, not scored as passes. Do not claim that Lean's pure classifier proves backend completeness or operational trust.

## Current decision

- **Known requirement:** durable history must preserve identity/provenance, exact snapshots and original assessments, atomic/idempotent writes, ordered retrieval after restart, corruption/failure reporting, and the distinction between original assessment and reassessment.
- **Unresolved:** runtime topology, cross-process/machine sharing, exact durability boundary, and operational trust boundary.
- **Not adopted:** backend family, deployment topology, cursor/snapshot semantics, identifier generation, or integrity/digest guarantee.
- **Not tested:** all real-backend integration gates.
- **Next justified action:** recover or establish the deployment requirements; then eliminate incompatible candidates before any backend is chosen.
