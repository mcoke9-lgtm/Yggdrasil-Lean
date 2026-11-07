# SCH-001 — Coordinator foundation comparison

**Status:** design comparison only; no production foundation selected, installed, or adopted.
**Scope:** compare (1) a transactional database, (2) a consensus-backed authority service, and (3) a deliberately limited single-host coordinator for the SCH-001 authority record and handoff protocol.

## Decision summary

There is no universal winner. The correct foundation depends on the required failure domain:

- **One scheduler process on one host, with manual recovery acceptable:** retain a deliberately limited single-host coordinator. It must fail closed on uncertainty and must not promise automatic failover.
- **Several processes or services already sharing one managed transactional database:** a database-backed authority record is the least additional infrastructure, provided the authority compare-and-advance occurs in one durable conditional transaction and the database deployment's failover semantics are understood.
- **Multiple hosts requiring automatic, partition-safe authority changes:** use a consensus-backed coordinator (or a mature service already providing equivalent guarantees). Accept that loss of quorum means authority changes stop.

For the current SCH-001 prototype, the recommended next step is **not** to select a production coordinator. Keep the local reference implementation explicitly non-activating; document a single-host operating mode as the safe interim boundary. Revisit a database adapter only if an existing suitable database is part of the actual deployment. Do not introduce a new consensus cluster merely to remove the current three-task ChatGPT limit.

## Comparison

| Dimension | Transactional database | Consensus-backed service | Limited single-host coordinator |
|---|---|---|---|
| Main guarantee | Atomic conditional update/transaction within one database authority | Ordered, quorum-committed authority updates across members | One active process owns the local state; no distributed claim |
| Competing writers | Safe only when compare and write are in the same database transaction/conditional statement, with correct isolation and conflict handling | Safe when using the service's atomic compare-and-swap/transaction API | Safe only under a real single-instance boundary; the current in-memory store-object mutex is not that boundary |
| Network partition | Depends on database topology and failover policy; clients must not independently promote stale primaries | Majority side can progress; minority side cannot commit | No remote failover; host/process unavailable means coordinator unavailable |
| Crash/restart | Committed transactions survive according to database durability and replication configuration; ambiguous client response must be resolved by stable transfer ID | Committed quorum entries survive leader changes; uncommitted requests may time out and must be queried by stable ID | Local restart can recover completed durable writes; power-loss and cross-process guarantees require explicit validation |
| Backup rollback | A restored old database snapshot can still roll authority history backward unless restore is fenced/reconciled against an independent monotonic source | Snapshot restore is a cluster-recovery operation, not permission to resume from stale authority without revision/consumer reconciliation | Stale local backup can reuse an epoch; quarantine and manual reconciliation required |
| Fencing | Database-issued generation is only useful if every side-effect boundary checks it | Consensus-issued generation is only useful if every side-effect boundary checks it | No automatic cross-host fencing; external actions must remain disabled or independently guarded |
| Operational burden | Moderate if an appropriate database already exists; otherwise database provisioning, credentials, backup, monitoring, failover and restore ownership | Highest: quorum members, networking, TLS/auth, monitoring, upgrades, snapshots, membership and quorum recovery | Lowest, but availability is limited to one host and one authority process |
| Appropriate use | Multi-process coordination within an existing database trust/failure boundary | Multi-host authority and automatic failover where quorum operations are justified | Local scheduler, manual recovery, no automatic failover |

## 1. Transactional database

A relational database can implement the authority compare-and-advance as one atomic conditional write, for example: update the task's authority row only where both the expected owner and expected epoch match; require exactly one affected row; and append the transfer receipt in the same transaction. A unique constraint on the stable transfer ID prevents duplicate receipts. If the transaction aborts or the client loses its response, query the original transfer ID before attempting anything else.

PostgreSQL documents row-update behavior under Read Committed and the stricter Serializable isolation level. Serializable transactions may be aborted and must be retried; retry the whole transaction, not only its final statement. The design must choose and test an isolation/locking strategy rather than assume that any transaction wrapper makes a read-then-write sequence safe.

**Strengths**
- Mature atomic transactions, constraints, backup tooling and operational knowledge.
- Natural fit if the deployment already runs a managed database.
- Lower operational complexity than introducing a separate consensus service.

**Limits**
- A database transaction only serializes writers within the authoritative database. It does not, by itself, prove safe multi-region promotion, stale-primary fencing, or rollback-resistant epochs.
- Database HA/failover behavior is deployment-specific. Split-brain prevention and promotion rules must be verified for the actual provider/topology.
- Restoring an old backup can restore an old authority epoch. Recovery must quarantine authority changes until the restored history is reconciled with a non-rollbackable witness or reviewed external record.

**Minimum acceptance conditions**
1. Compare owner + epoch + task-definition fingerprint and advance epoch in one conditional transaction.
2. Persist transfer receipt and current authority atomically.
3. Enforce unique stable transfer IDs and immutable request fingerprints.
4. Define retry behavior for serialization failure, deadlock, timeout and lost response.
5. Document database replication/failover guarantees and backup-restore quarantine.
6. Demonstrate that the execution boundary rejects stale generations.

## 2. Consensus-backed authority service

A service such as etcd provides atomic If/Then/Else transactions guarded by comparisons. This directly supports compare-and-swap on the authority key and can serialize updates through a quorum-backed log. It is the strongest fit when several hosts must coordinate authority across network partitions.

**Strengths**
- A single ordered authority history across participating members.
- A minority partition cannot independently commit a competing authority update.
- Built-in transaction and revision primitives are suitable for stable transfer IDs and authority generations.

**Limits**
- A quorum is an availability dependency: when a majority is unavailable, authority writes stop. This is a safety property, not a defect to bypass.
- It adds member lifecycle, network, security, monitoring, upgrade and disaster-recovery work.
- Snapshots are not a magic rollback-proof witness. The official recovery guidance describes restoring a new logical cluster and handling revision rollback; SCH-001 must still define how old consumers and authority epochs are quarantined/reconciled.
- Consensus does not fence arbitrary external systems. Each effect boundary must reject stale authority generations.

**Minimum acceptance conditions**
1. Use atomic compare-and-swap/transactions; no client-side read-then-write authority mutation.
2. Require authenticated clients and encrypted transport.
3. Define quorum-loss behavior: no authority change, no automatic fallback to local JSON.
4. Define stable transfer-ID recovery after timeouts and leader changes.
5. Define snapshot restore, revision/epoch reconciliation, member replacement and cluster identity procedures.
6. Enforce generations at every side-effect boundary and test stale-source resumption.

## 3. Deliberately limited single-host coordinator

This option explicitly accepts that the authority service is available only on one host and through one controlled process. If that process or host is unavailable, the system stops authority changes. There is no automatic promotion to another host and no claim that a local epoch is a distributed fencing token.

**Strengths**
- Smallest operational footprint.
- Fits the current local Node.js scheduler shape.
- Avoids introducing a new database or consensus cluster before a real multi-host requirement exists.

**Limits**
- The current SCH-001 implementation is not yet a safe multi-process single-host authority service: its shared lock is keyed by JavaScript store-object identity. Separate store objects/processes can race.
- The JSON snapshot can be restored to an older epoch.
- Current file persistence tests do not prove power-loss durability or cross-process serialization.
- It cannot safely automate failover or authorize side effects on another host.

**Minimum acceptance conditions**
1. Enforce one scheduler/coordinator process with an OS-level lock or equivalent process boundary.
2. Keep authority mutation and execution in that same controlled authority domain.
3. Treat missing, corrupt, replaced or restored state as DEGRADED/quarantined; never infer ownership from timestamps or reachability.
4. Disable automatic failover and cross-host activation.
5. Keep handler side effects disabled unless an independent effect-boundary check exists.
6. Test process crash, restart, concurrent launch, partial write, disk-full and backup-restore scenarios.

## Decision gates

Choose only after answering these deployment questions:

- Is the scheduler strictly one process on one host, or must multiple processes/hosts coordinate?
- Is automatic failover a requirement, or is manual recovery acceptable?
- Is there already a managed transactional database with documented failover and restore behavior?
- Which external systems can enforce and verify the authority generation before performing an action?
- What is the required recovery point and recovery time, and who owns backups and incident response?

If the deployment is local-only and manual recovery is acceptable, use the limited single-host mode and keep it non-activating until its process lock and durability tests pass. If an existing managed database is available and multiple processes must coordinate, evaluate the database adapter first. If multi-host automatic failover is required, evaluate a consensus service and its operational cost explicitly; do not approximate it with JSON files, timestamps, heartbeats or leases alone.

## Relationship to the current prototype

The current AuthorityCoordinator remains LOCAL_REFERENCE_GRANT_ONLY. The adversarial audit has already reproduced two critical failures: competing independent store clients can both grant the same epoch, and restoring an older JSON snapshot can reuse an epoch. This comparison does not repair those defects.

No database adapter, consensus client, OS-level coordinator lock, external fencing integration, task activation, automatic failover, production migration, merge or deployment is implemented or authorized by this document.

## Primary references

- PostgreSQL, [Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html): row-update behavior, Serializable guarantees and transaction retry requirements.
- PostgreSQL, [Data Consistency Checks at the Application Level](https://www.postgresql.org/docs/17/applevel-consistency.html): explicit locking and application-level consistency concerns.
- etcd, [API: Transactions](https://etcd.io/docs/v3.7/learning/api/): atomic If/Then/Else transactions and compare operations.
- etcd, [Failure Modes](https://etcd.io/docs/v3.7/op-guide/failures/): leader changes, quorum loss and network partition behavior.
- etcd, [Disaster Recovery](https://etcd.io/docs/v3.7/op-guide/recovery/): snapshot restore, revision rollback and recovery considerations.
