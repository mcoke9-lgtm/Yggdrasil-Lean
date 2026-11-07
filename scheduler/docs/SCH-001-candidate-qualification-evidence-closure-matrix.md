# SCH-001 Candidate Qualification and Evidence Closure Matrix

Date: 2026-11-07
Status: PROPOSED REVIEW MATRIX — NO OWNER ATTESTATION RECEIVED IN THIS STEP

## Decision

No candidate currently satisfies the SCH-001 independence requirements. The next action is not to create another free account or deploy a witness. It is to close the highest-value evidence gaps for existing accounts, beginning with Cloudflare only if an already-existing account is available and its owner can confirm its administrative and recovery boundaries.

Neon remains a conditional comparison candidate, not an assumed existing account. Supabase remains deprioritized. GitHub, Airtable and Dropbox retain their limited supporting roles.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## Candidate qualification sequence

A candidate must pass these stages in order. Failure or UNKNOWN at an earlier stage blocks later qualification claims.

1. **Existence and ownership:** identify the actual account and legal/operational owner; distinguish personal account, team account and organization.
2. **Administrative separation:** identify all effective administrators, support escalation powers and organization-level controls relevant to the service.
3. **Recovery separation:** identify who can recover the account, reset authentication, restore snapshots, change billing ownership or reclaim the service.
4. **Credential separation:** show that scheduler and executor identities cannot administer, rewrite, restore or sign witness state.
5. **Continuity and transaction semantics:** demonstrate monotonic conditional append and rollback detection against a continuity anchor outside the candidate's privileged control domain.
6. **Destination enforcement:** show that every protected external side-effect destination checks current generation and scope atomically at commit time.
7. **Independent reproduction:** a reviewer with separate credentials reproduces the evidence and adversarial tests.
8. **Explicit adoption:** only after all gates pass may the candidate be considered for adoption; activation still requires separate explicit authorization.

## Evidence closure matrix

| Evidence category | Minimum acceptable redacted evidence | What does not count | Current status |
|---|---|---|---|
| Account ownership | Provider account type and owner-controlled account identifier, redacted as needed | Assumption based on a project name | UNKNOWN for D1; Neon/Supabase not established |
| Effective admins | Current admin/role listing including inherited org/team roles | Screenshot of one user's role only | UNKNOWN for D1; Neon/Supabase not established |
| Recovery authority | Documented recovery and support escalation routes; who can reset MFA or transfer ownership | “Only I know the password” | UNKNOWN |
| Scheduler separation | Identity/role map showing scheduler/executor cannot administer witness | Different API token names under same super-admin | UNKNOWN |
| Signing-key custody | Key-management boundary, allowed signing identity, rotation and revocation process | Key stored as a scheduler secret | UNKNOWN |
| Conditional append | Reproducible concurrent test showing one winner for an expected generation and transaction abort on failed guard | Sequential happy-path test or batch success alone | D1 local/workerd prototype only; live status UNKNOWN |
| Monotonic recovery | Restore/rollback rehearsal showing independently held generation cannot be lowered or reset | Provider Time Travel alone | UNKNOWN |
| External anchor | Evidence anchor is administered/recovered outside candidate and scheduler admin domains | Second database under same owner/admin | UNKNOWN |
| Destination fence | Destination-side test that stale generation is rejected atomically with operation commit | Scheduler preflight check | UNKNOWN |
| Reviewer independence | Reviewer identity, separate authentication and reproducible evidence receipt | Same admin exporting a second copy | UNKNOWN |

## Candidate-specific disposition

### Cloudflare D1

Priority: first candidate for evidence closure, not adoption.

Known:
- A local/workerd prototype has 21/21 documented CI tests, including competing same-generation appends and local rollback/corruption cases.
- This is not live Cloudflare evidence.
- Earlier account inventory did not establish Cloudflare account ownership, admin roster, recovery path, signing-key custody or an external continuity anchor.
- No Cloudflare database or Worker was created in this work.

Required next evidence:
- Confirm whether an existing Cloudflare account/project is already available; do not create one for this review.
- Provide redacted account type and effective administrator/recovery-role evidence.
- Identify whether a separate owner/admin domain can hold the continuity anchor and signing keys.
- Only after custody gates are plausibly separable, plan an isolated live CAS and rollback rehearsal.

### Neon

Priority: conditional SQL comparison only.

Known:
- No existing Neon account or project was established in the inspected inventory.
- No Neon schema, CAS test or restore rehearsal has been run.

Required next evidence:
- Establish whether an existing account is available without creating one.
- Confirm owner/admin/recovery separation before any project or credential work.
- If no suitable existing account exists, leave Neon untested and do not create an account merely to advance the project.

### Supabase

Priority: deprioritized under free-tier continuity requirements.

Known:
- No existing account/project has been established in the inspected inventory.
- Free-tier pause and backup constraints make it a weaker candidate for an always-on witness.

Required next evidence:
- Reconsider only if an already-existing account has a materially stronger independent custody boundary and availability design than currently known.
- No new project or account is justified at this stage.

### GitHub, Airtable and Dropbox

Retain existing bounded roles:
- GitHub: source history, review artifacts and CI evidence.
- Airtable: disposable pilot records.
- Dropbox: possible human-readable checkpoint storage after custody is established.

None is promoted to witness authority by this matrix.

## Safe owner-confirmation format

Evidence can be summarized without sharing secrets. A useful attestation states:
- service and account type;
- whether the account is personal, team or organization-owned;
- count and role categories of effective administrators;
- whether recovery/support can override those administrators;
- whether scheduler/executor identities share any administrator, recovery or signing-key authority;
- whether a continuity anchor is held under a different owner and recovery domain;
- date observed and evidence source;
- what remains unknown.

Do not send passwords, API tokens, recovery codes, private keys, unredacted account identifiers or security-question answers in chat. A redacted description or owner-prepared evidence summary is sufficient for initial triage; sensitive evidence should be reviewed through an appropriate secure channel.

## Stop conditions

Stop qualification and retain UNKNOWN if:
- the account owner or effective administrator set cannot be established;
- recovery authority is opaque or overlaps scheduler/executor control;
- the candidate and its continuity anchor share a common super-admin or recovery path;
- generation monotonicity depends only on the candidate's own restorable database;
- a destination cannot enforce current generation and scope at commit;
- a reviewer cannot independently authenticate and reproduce the evidence.

A candidate that fails a hard gate is not rescued by a high score elsewhere.

## Integrity classification

- Evidence closure matrix: PROPOSED.
- Existing account facts: inherited from prior SCH-001 inventory; not re-audited here.
- Owner attestations received in this step: NONE.
- Live provider tests: NONE.
- New accounts/projects/resources: NONE.
- Credentials or permissions changed: NONE.
- Candidate adopted: NONE.

## Integrity boundary

This document does not provision services, request secrets, alter permissions, run live tests, activate tasks or transfer authority.
