# SCH-001 — Independent witness custody and compromise review

**Status:** proposed threat-model review only. No live provider or custodian has been selected or audited.

**Prototype baseline:** local D1/workerd tests D1L-01–D1L-21 passed on implementation commit `4ad9a7aa5df360b5b8177de7f4d0eaa09d2cba9f`, CI run 42334904991. The later evidence-documentation commit is `310122c64c55c9569077d3714e2db359c7c8967b`. The Worker, local journal and runner-retained comparison receipt share one GitHub Actions host. They do not protect against compromise of that host or its administrators.

## 1. Security question and present answer

Could an attacker controlling the D1 account, Worker deployer, scheduler host, CI runner, anchor operator or recovery administrator rewrite/roll back authority history and persuade a destination to act on stale or fabricated authority?

**Present answer:** the prototype cannot rule this out. The local journal and comparison receipt share a host and execution context. A receipt held by the same privileged actor is not an independent witness.

## 2. Assets and non-compensatory properties

| Asset | Required property | Failure consequence |
|---|---|---|
| Authority stream | One ordered history per task | Conflicting owners or replay |
| Event identity | Stable ID bound to canonical payload | Duplicate or substituted authority |
| Sequence/generation | Monotonic; never silently reset | Stale owner appears current |
| Witness receipt | Durable, queryable, independently retained | Rollback goes undetected |
| Signing keys | Scoped, attributable, recoverable and revocable | Forged or unverifiable checkpoints |
| Recovery evidence | Survives primary service and host loss | Restore relies on incomplete history |
| Destination fence | Rejects stale generations at every side-effect boundary | Both source and destination act |

A strong hash chain cannot compensate for shared administrator access. A signature cannot compensate for a compromised signer. Two storage resources cannot compensate for shared restore authority.

## 3. Compromise matrix

| Compromised role | Capabilities to assume | Current protection | Required independent control |
|---|---|---|---|
| Scheduler/source host admin | Alter scheduler/local state, steal host tokens | No independent protection established | Witness authorization not derived solely from this host; destination checks generation |
| D1 Worker deployer | Deploy malicious code, misuse Worker bindings | Local code tests only | Narrow permissions; separately controlled deploy approval; anchor verifies signed envelopes |
| D1 account owner/admin | Restore/replace database, alter secrets, bindings and Worker | Local rollback simulations only | Anchor outside D1 account with distinct owner, recovery credentials and retention controls |
| CI runner/repository admin | Alter tests, workspace, local receipt and artifacts | Not protected; same test boundary | CI is provenance, never production authority witness |
| Anchor service operator | Delete, reorder, restore or deny anchor records | No real anchor tested | Append-only/retention controls or independently detectable history |
| Anchor account/recovery admin | Reset credentials, change retention, restore account | Not tested | Separate recovery principal and offline/third-party checkpoint |
| Signing-key custodian | Sign, revoke, destroy or replace key | No production key custody | Offline/HSM-backed key, dual-control rotation, independent public-key retention |
| Destination operator | Activate destination or bypass stale-generation checks | No external fence | Enforcement at actual side-effect provider; otherwise activation disabled |
| Independent reviewer | Misstate evidence or collude | No independent review | Read-only access, conflict declaration, preserve signed evidence |
| Cloud organization super-admin controlling D1 and anchor | Control both despite separate resource names | Not protected | Separate administrative organizations or genuinely independent custodian |

## 4. Collusion and common-mode failures

Nominal separation fails if:
- D1 admin and anchor admin are the same principal;
- Worker deployer also controls the signing key;
- CI admin can rewrite both the test artifact and retained comparison receipt;
- one organization super-admin controls both cloud accounts/resources;
- destination and scheduler administrators can bypass the generation fence;
- one person holds every recovery credential.

A custody inventory must identify actual principals and organization/account ownership, recovery email/phone, MFA and recovery methods, API scopes, backup restore rights, retention controls, billing/support escalation, audit-log control and key custody. Separate boxes in an architecture diagram are not proof.

## 5. Minimum separation candidate

Before live authority use, require:

1. D1 is treated as rewritable by its account administrator.
2. Continuity anchor is in a separate provider account and distinct organization/admin principal, with no shared super-admin or sole recovery controller.
3. Anchor supports stable checkpoint IDs, expected prior sequence/hash, conditional append, immutable receipt lookup and same-ID retry.
4. Delete/overwrite/restore is prevented or independently detectable. If an administrator can silently replace the entire anchor history, it is not independent.
5. Signing key is unavailable to D1 and scheduler administrators; key transitions are anchored before use.
6. Signed offline checkpoints are held by an independent custodian; same-cloud exports do not qualify.
7. Restore, key replacement, migration and baseline reset require two distinct authorized people, one outside primary D1 administration.
8. Destination checks current witnessed generation at every side-effect boundary; unavailable or stale means reject.
9. Reviewer retrieves anchor evidence directly, using credentials not issued by the D1 administrator.

This is a design target, not a claim that a particular provider meets it. A second account under the same organization may reduce routine blast radius but does not defeat organization-level compromise.

## 6. Recovery and key transitions

- D1 restore/rollback: quarantine; compare with latest independently retrieved anchor sequence/hash; never synthesize missing events.
- Anchor restore/rollback: continuity is lost unless an independently held signed checkpoint proves the latest accepted head.
- Ambiguous append: query same stable checkpoint ID and exact canonical payload hash; unresolved means quarantine.
- Key rotation: retain old public verification keys; anchor new key ID, fingerprint, effective sequence and dual approvals before use.
- Key loss/compromise: stop appends/transfers; preserve evidence; require independent reauthorization.
- Custodian departure: dual-control succession or remain quarantined.
- Provider suspension, billing lock or account recovery: no local fallback authority.
- Anchor migration: dual-write/compare an overlap period; independent reviewer verifies sequence and signed export before retiring old anchor.

## 7. Evidence required to call an anchor independent

Collect redacted evidence for:
- legal/organizational ownership of primary and anchor providers;
- named human roles and separate recovery contacts;
- permission exports for read, append, delete, restore, retention, deploy and key administration;
- MFA/recovery-path separation;
- audit-log retention and independent export;
- append-only/retention-lock semantics and exceptions;
- restore exercise with an external checkpoint surviving;
- key generation, custody, rotation and recovery;
- stale-generation rejection at the real destination side-effect boundary;
- reviewer sign-off naming evidence, limitations and unresolved dependencies.

Never put passwords, API tokens, seed phrases, recovery codes or private keys in chat.

## 8. Adversarial follow-up tests — design only

| ID | Test | Required outcome |
|---|---|---|
| CUST-01 | D1 admin rewrites all D1 rows and Worker state | Independent anchor detects mismatch |
| CUST-02 | CI admin alters local journal and retained receipt | Demonstrates same-domain compromise; no independence claim |
| CUST-03 | D1 and anchor share one organization super-admin | Independence gate fails |
| CUST-04 | Anchor admin deletes/restores records or downgrades retention | Denied or independently detected |
| CUST-05 | Stolen Worker token without anchor credential | Cannot rewrite anchor or sign checkpoint |
| CUST-06 | Worker submits fabricated envelope without signer | Anchor rejects |
| CUST-07 | Signing custodian unavailable | Appends/transfers stop; old receipts remain verifiable |
| CUST-08 | Key rotation missing one approver | Rotation rejected |
| CUST-09 | Anchor account recovery changes owner/contact | Quarantine pending independent confirmation |
| CUST-10 | D1 and anchor restored from coordinated old backups | Third checkpoint detects rollback or architecture fails |
| CUST-11 | Destination API bypass attempt | External fence rejects stale generation; otherwise no safe activation claim |
| CUST-12 | Reviewer retrieves anchor with separate credentials | Exact sequence/hash independently reconstructed |

Simulated roles in one CI process can test logic, not real organizational separation.

## 9. Decision

**WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.**

- Preserve the 21/21 local CI baseline.
- Do not label the local journal or CI receipt an independent witness.
- Do not select D1 as production authority or provision another provider/account.
- Do not connect scheduler, Android or destination to authority-transfer flows.
- Do not enable transfer, automatic failover or activation.
- Next step is a permission/custody inventory of existing services using redacted metadata and explicit owner confirmation. If no independent administrative domain exists, record the limitation and remain in prototype state.

**Integrity:** proposed threat model and acceptance criteria only. No account, role, credential, key, provider policy, backup, deployment, device or service was inspected or changed. No independent security audit was performed.
