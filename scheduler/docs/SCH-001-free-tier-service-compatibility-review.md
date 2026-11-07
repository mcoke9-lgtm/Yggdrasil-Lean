# SCH-001 Free-Tier Service Compatibility Review

Date: 2026-11-07
Status: DOCUMENTARY COMPARISON — NO NEW SERVICE CONNECTED OR PROVISIONED

## Executive decision

No currently evidenced service qualifies as an independent SCH-001 custody witness.

Cloudflare D1 remains the strongest already-prototyped transactional candidate for continued isolated technical testing, but its free-plan limits, Cloudflare account administration, Time Travel restore authority and absence of a verified external continuity anchor prevent adoption.

Neon Free is a plausible PostgreSQL concurrency/CAS test candidate, but it is not an existing connected account in the inspected inventory and its free plan does not itself supply independent administrative custody. Supabase Free is less suitable for a continuously relied-upon witness because projects may pause after low activity and the free plan has no automatic backups.

GitHub, Airtable and Dropbox remain useful for source history, pilot records and human-readable artifacts, respectively; none is qualified as the independent authority witness.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## Evidence basis and date

Provider documentation checked 2026-11-07. Plan limits and product behavior can change; re-check before any future implementation.

### 1. Cloudflare D1 — best existing prototype candidate, not qualified

Documented:
- Free: 10 databases, 500 MB/database, 5 GB total account storage, seven days of Time Travel; current D1 limits page also lists 50 read subrequests per Worker invocation and other execution constraints. [Cloudflare limits](https://developers.cloudflare.com/d1/platform/limits/)
- D1 is single-threaded per database. [D1 FAQ](https://developers.cloudflare.com/d1/reference/faq/)
- `db.batch()` executes statements sequentially as one SQL transaction; a statement failure aborts/rolls back the batch. [D1 Database API](https://developers.cloudflare.com/d1/worker-api/d1-database/)
- Free-plan daily read/write quota exhaustion blocks queries until reset; storage exhaustion prevents further inserts/schema changes until capacity is freed or plan changes. [D1 FAQ](https://developers.cloudflare.com/d1/reference/faq/)
- Time Travel is always on for supported current-storage databases; the documented free retention is seven days, and restores are supported. [Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/) [Limits](https://developers.cloudflare.com/d1/platform/limits/)

Assessment:
- Transaction primitive: promising and already exercised in local/workerd prototype, not live Cloudflare.
- CAS: possible to construct with conditional update/insert and explicit guard that turns a zero-row condition into transaction failure; batch atomicity alone is not CAS.
- Restore/rollback: recovery convenience, not an independent continuity anchor. A privileged Cloudflare account actor may control both database and restore path unless a separate administrative domain is proven.
- Availability: quota exhaustion blocks queries; free limits make it unsuitable as sole always-available witness.
- Custody: existing Cloudflare account ownership, admin roster, recovery authority, signing-key separation and account boundary have not been verified in this work.
- Verdict: retain as isolated prototype candidate only. Do not put canonical authority or live task activation on it.

### 2. Neon Free — possible PostgreSQL CAS test candidate, not an existing verified service

Documented free-plan terms:
- $0 plan: up to 100 projects, 10 branches/project, 100 compute-hours/project/month, 1 GB Postgres storage/project, 5 GB public transfer/project/month.
- Compute scales to zero after five minutes of inactivity.
- Instant restore history is six hours, capped at 1 GB of change history.
- If compute or transfer quota is exhausted, compute is suspended until reset or upgrade. [Neon Free quotas](https://github.com/neondatabase/website/blob/main/content/faqs/free-plan-limits-and-quotas.md)

Assessment:
- PostgreSQL provides a familiar transactional foundation for conditional writes and row locking, but no live Neon project or SCH-001 schema has been created or tested.
- Scale-to-zero and finite compute quotas mean it cannot be presumed continuously reachable.
- Branching and restore are useful for development and recovery, not independent custody.
- Neon account administrators and recovery authority would still control the service; independent separation from scheduler/executor admins is unverified.
- Verdict: candidate for a future isolated SQL/CAS comparison only, subject to account ownership and admin-boundary evidence. Not currently connected or qualified.

### 3. Supabase Free — technically capable Postgres, weaker operational fit for a witness

Documented:
- Two free projects across organizations where the user is owner/admin. [Supabase billing](https://supabase.com/docs/guides/platform/billing-on-supabase)
- A free project with low activity over a seven-day period may be paused. [Pausing](https://supabase.com/docs/guides/platform/free-project-pausing)
- Free plan has no automatic daily database backups; manual exports are available. [Backups FAQ](https://supabase.com/kb/faqs/platform/free-plan-backups) [Backup docs](https://supabase.com/docs/guides/platform/backups)

Assessment:
- PostgreSQL transaction semantics could support CAS in principle; no project/schema/integration has been tested.
- Automatic pausing is a poor fit for a witness expected to be available on demand.
- No automatic backup on Free increases recovery burden.
- A Supabase project under the same owner/admin and recovery control as scheduler/executor would not be independent.
- Verdict: do not prioritize as the witness candidate under the free-tier constraint.

### 4. GitHub — source/evidence history, not authority witness

Observed account facts in the existing inventory:
- Repository is public under the connected personal account; connected account has admin permission.
- Full collaborator roster, rulesets/branch protection, recovery/MFA and all credential custody remain unverified.
- Git history and content API can preserve reviewable artifacts, but the same repository admin can alter repository state; a Git commit is not an independent witness receipt.
- Verdict: retain for source control, reproducible artifacts and CI evidence only.

### 5. Airtable — pilot record store, not authority witness

Observed account facts:
- Existing pilot base permission: create.
- One accessible workspace, connected account owner.
- Full workspace member, enterprise, recovery and automation credential boundaries remain unverified.
- No verified conditional-append primitive with required monotonicity and independent recovery authority has been established.
- Verdict: retain for pilot records and review, not canonical witness authority.

### 6. Dropbox — human-readable checkpoint store, not authority witness

Observed account facts:
- Connected account is personal, not a team account.
- Project-specific files were not found by prior connected searches; this does not prove the account is empty.
- Recovery authority, MFA, sharing policy and independent custody are unknown.
- File sync and sharing do not establish transactional CAS, monotonic continuity or destination-side fencing.
- Verdict: possible human-readable/offline checkpoint location only after separate custody and recovery evidence; not a machine authority witness.

## Comparative matrix

| Service | Transaction/CAS potential | Free-plan continuity | Custody evidence | SCH-001 status |
|---|---|---|---|---|
| Cloudflare D1 | Batch transaction; CAS must be explicitly guarded | Daily quotas can block; 7-day restore history | Not independently established | Prototype candidate |
| Neon Free | PostgreSQL transaction/CAS potential; untested here | Scale-to-zero; 100 CU-hours/project/month; 6-hour restore | Not established; not known as connected account | Future test candidate |
| Supabase Free | PostgreSQL transaction/CAS potential; untested here | May pause after 7 low-activity days; no automatic backups | Not established; not known as connected account | Deprioritize |
| GitHub | Versioned commits; not an independent transactional witness | CI and repo availability subject to account/service controls | Same connected personal admin domain | Source/evidence only |
| Airtable | Pilot record API; required CAS/monotonicity unproven | Service/plan quotas and owner controls | Connected account owner; wider recovery/admin scope unknown | Pilot only |
| Dropbox | File operations; required CAS/monotonicity unproven | Personal account/service availability | Personal account; recovery controls unknown | Human-readable checkpoint only |

## Recommended next decision

Do not create another free account simply to make progress.

First, obtain redacted, owner-confirmed evidence for the actual administrative and recovery boundaries of the already-existing accounts. If D1's account boundary cannot be separated from scheduler/executor authority, it cannot qualify regardless of transaction quality.

If a separate owner-controlled account/domain can be demonstrated, compare D1 and Neon in isolated tests:
1. Concurrent conditional append: exactly one winner for a given expected generation.
2. Zero-row guard must abort the whole transaction.
3. Process restart and concurrent worker race.
4. Restore/rollback detection against a separately held continuity receipt.
5. Quota exhaustion and provider outage => UNKNOWN, never ALLOW.
6. Key rotation and restore succession require independently witnessed continuity.
7. Destination fence rejects stale generations at the side-effect boundary.

The free plan is a cost constraint, not an independence guarantee. A second service from the same administrator/recovery domain is not an independent witness.

## Evidence classification

- Provider plan and feature descriptions: DOCUMENTED by provider sources linked above.
- Existing GitHub/Airtable/Dropbox account observations: previously inspected and recorded in SCH-001 account inventory; not re-audited in this document.
- D1 local/workerd tests: prior local CI evidence only; not live-provider evidence.
- Neon/Supabase CAS behavior in SCH-001: NOT TESTED.
- Independent custody for every candidate: NOT VERIFIED.
- New accounts, projects, databases, workers, credentials or integrations: NONE CREATED.
- Adoption: NONE.

## Integrity boundary

This is a documentary compatibility review. No external service was provisioned or connected, no credentials were requested or changed, no live task was activated, and no authority was transferred.
