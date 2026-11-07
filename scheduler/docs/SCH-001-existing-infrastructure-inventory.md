# SCH-001 — Existing infrastructure inventory and witness suitability

**Status:** read-only inventory and design assessment recorded on the SCH-001 development branch. No service, device, credential, access rule, or database was changed. This is not a full account-wide security audit.

## Executive result

The project already has useful scheduling, execution, encrypted local storage, event/run history, source control, CI, and candidate host designs. It does **not** yet have a verified independent monotonic authority witness.

The strongest next path is to reuse the existing Nine Builders host/runtime work and evaluate the already identified physical NUC as a possible separate control-plane node, while keeping the witness implementation unselected until actual host availability and independent custody are verified. The Android device/runtime is another possible node, but its app is not installed and its local SQLite/Keystore storage is not an independently verified monotonic authority service.

Do not create a new database or consensus cluster yet.

## 1. Inventory

| Existing resource | Evidence found | Useful role | Witness suitability now |
|---|---|---|---|
| GitHub repository and Actions | Connected repository, development branches, CI workflows | Source history, review, build/test evidence | **Not sufficient.** Relevant branches are unprotected; Git history and Actions are not a conditional, independently administered monotonic authority ledger. |
| Nine Builders Node runtime | Branch `nine-builders-overnight-runtime-001`; encrypted task vault, run ledger, dedicated runner, operator-supplied host modules | Existing runtime, task/result history, encrypted prompt storage | **Not sufficient.** State and vault are host-local files with local locks; no independent authority sequence, external fencing, or verified remote restore witness. |
| Android scheduler/Builder host | Branch `android-scheduler-host-prototype-001`; SQLite task/run store and WorkManager; Builder overnight path on PR #43 | Candidate second physical execution node and user-facing host | **Not sufficient now.** Source/CI exists, but no Galaxy S21 installation or device test is verified. SQLite is device-local; WorkManager is an execution scheduler, not a monotonic authority witness. |
| Intel NUC5i5RYH | Existing project assessment identifies user's access to a prototype; proposed Debian 13 minimal server | Candidate independent control-plane/continuity node | **Potential future host, not currently available as a service.** Actual device, installed RAM/storage, OS, network and uptime are unverified. The OS-selection document says no OS was installed at that checkpoint. |
| Airtable — Adaptive Filesystem ChatGPT Pilot | Connected base; permission level `create`; Records table includes stable Record ID, Project ID, Branch ID, parent, sequence, event type, content, status and evidence references | Project checkpoint/evidence register and human-readable history | **Not sufficient.** Base description explicitly calls it a disposable feasibility pilot, not the canonical archive. No SCH-001 witness records were returned by the targeted search. Conditional append, immutability, independent administration, and restore continuity are not established. |
| Dropbox connector | Connector is available in this ChatGPT environment | Potential file exchange/backup surface | **Unassessed and not counted.** No project-specific independent witness service, custody model, or monotonic append capability has been verified. |

## 2. Nine Builders Node runtime — most reusable existing work

The later branch already includes:
- a durable nine-role run ledger with role attempts, outcomes, checkpoints, deadline handling, and safe-resume gates;
- a separate encrypted task vault using AES-256-GCM, with host-supplied 32-byte key material, owner-only file handling and mutation locking;
- a dedicated runner that uses distinct scheduler state, lock and dashboard configuration;
- a read-only GitHub evidence gateway;
- explicit user approval and opaque task references;
- cancellation, interrupted-run and external-outcome-unknown handling.

This is meaningful runtime infrastructure and should be reused rather than duplicated.

It does not meet the witness requirement:
- the task vault and scheduler/run stores are local files;
- local locks do not provide cross-host conditional append;
- encryption protects confidentiality/integrity under key custody assumptions, not rollback freshness;
- a restored old vault/state and its local metadata can be internally valid;
- the GitHub evidence gateway is read-only and cannot serve as the authority write path;
- the host executor and provider routes remain operator-supplied integration boundaries, not configured production services.

PR #43 is open, draft and unmerged. Its latest inspected head is `667e16ddd3a0bacaebe63444d4e14c516bf71849`. Four workflows on that exact SHA completed successfully: Nine Builders Scheduler Runtime Tests, Analysis Engine, Android unit tests, and Lean build/analysis. This is repository CI evidence only, not live host or provider verification.

## 3. Android host — candidate independent node, not current witness

The Android branch contains a local SQLite scheduler database and WorkManager scheduling. The later Nine Builders runtime adds a dedicated thread, Android Keystore AES-GCM prompt encryption, explicit approval, cycle checkpoints and Stop notification.

The project documentation explicitly says:
- no APK installation on the Galaxy S21 has occurred;
- no device-level execution test or unattended overnight run is verified;
- WorkManager timing is not hard real-time;
- mid-cycle recovery is manual and does not automatically replay uncertain work.

A separate physical Android device could eventually be a failure-domain-separated witness client or operator console for a NUC host, but only after installation, authenticated communication, independent custody, and a real monotonic/conditional append service are established. Its local SQLite database alone does not meet the witness acceptance gates.

## 4. NUC — best existing host candidate, still unverified

The existing host assessment proposes the Intel NUC5i5RYH as a small control-plane and continuity node for API routing, task coordination, audit records, and a small database. The actual unit has not been inspected in this work. A separate OS-selection document says the target was reported without an operating system installed and proposes a minimal Debian server installation.

The NUC could host a witness service if:
- the unit is physically confirmed and brought online under user approval;
- storage, power, network, clock, backups and recovery are tested;
- witness data and administration are separated from the scheduler host's normal state/backup path;
- the service provides transactional conditional append and durable acknowledged receipts;
- a second independent recovery checkpoint protects the witness itself.

A NUC running another JSON file beside the scheduler is not enough. It is a separate machine, not automatically an independent administrative or recovery trust domain.

No installation or hardware action is authorized by this inventory.

## 5. Airtable pilot — useful record register, not authority

The connected base is `Adaptive Filesystem — ChatGPT Pilot`, with permission level `create`. Its Records schema is suitable for project identity, branch lineage, sequence labels, event classification, status, evidence references and checkpoints.

However:
- the base's own description says it is disposable feasibility storage and not the canonical archive;
- the targeted SCH-001 search returned no witness records;
- a prior owner-review checkpoint records that the writer inventory is unresolved: collaborators, workspace/group access, API tokens, scripts, integrations, automations, scheduled jobs, and manual write paths were not all independently accounted for;
- connector-visible access does not prove all writers are forced through one coordinator;
- Airtable record creation/update does not establish a serialized monotonic CAS authority boundary or rollback-proof immutable log.

Therefore Airtable may continue to hold human-readable project checkpoints, but must not be treated as the production authority witness without a separately approved redesign and owner-verified writer/access audit. No Airtable records or permissions were changed in this inventory.

## 6. Witness acceptance-gate assessment

| Acceptance gate | Current evidence | Result |
|---|---|---|
| Independent failure/administrative domain | GitHub, Airtable, Android, NUC candidates exist, but actual custody and service topology are not established | OPEN |
| Conditional append/CAS under competing writers | Local process locks only; no independent transactional witness tested | FAIL / NOT PRESENT |
| Durable receipt after commit | Local file persistence and Airtable records exist; no witness receipt contract | NOT PRESENT |
| Immutable/append-only authority history | Git history and project records are mutable; no witness append-only policy | NOT PRESENT |
| Stable event ID and same-ID retry | SCH-001 conceptual protocol and local handoff IDs exist; no remote witness implementation | DESIGN ONLY |
| Authenticated authority authorization | User-adoption signatures exist in the Builder runtime, but they are not a scheduler authority-witness service | PARTIAL / WRONG SCOPE |
| Rollback continuity for witness itself | No independent monotonic checkpoint or witness recovery evidence | NOT PRESENT |
| Execution-boundary fencing | Source relinquishment/fencing contract only; no external boundary implementation | NOT PRESENT |
| Actual host availability | NUC uninspected/uninstalled; Galaxy S21 app not installed/tested | NOT VERIFIED |
| Independent review and adoption | PR #43 remains draft/unmerged; no witness review/adoption | NOT PRESENT |

## 7. Recommended sequence

1. **Reuse, do not duplicate:** treat the Nine Builders Node runtime and Android host work as existing execution components. Keep SCH-001's authority witness a separate narrow service boundary.
2. **Verify the physical NUC only when available:** collect OS, RAM, storage health/free space, wired network, power/uptime and backup destination. No disk changes without explicit user approval.
3. **Choose the failure domain:** decide whether the NUC can host the witness while the Android phone is an independently controlled client/operator, or whether another existing independently administered service is preferable.
4. **Test an existing transactional service only if one is identified:** no existing PostgreSQL, etcd, or equivalent service was confirmed in this inventory. Do not infer one from comparison documents.
5. **Prototype conditional append before integration:** test competing writers, lost receipts, same-ID retries, stale generation rejection, witness restart and witness backup rollback.
6. **Keep activation disabled:** until every required external side-effect boundary enforces fencing and the witness's own restore continuity is demonstrated.

## 8. Scope and integrity notes

- This was a repository and connected Airtable inventory, not a full cloud-account, device, network, or collaborator security audit.
- Dropbox was not inspected for project-specific artifacts or administration.
- No host was started, no device was contacted, no credential was read, no provider was configured, and no infrastructure was provisioned.
- No task, Airtable record, branch protection rule, PR state, or deployment setting was changed.
- Existing development branches are not production installations. CI success is not a device or live-service test.
- Proposed: NUC as a possible independent witness host, with Android as a separate client/secondary node.
- Not selected: any database, consensus provider, or new service.
- Not implemented: remote witness, conditional append, monotonic external checkpoint, production fencing, automatic failover, or task activation.
