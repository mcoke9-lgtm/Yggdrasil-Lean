# SCH-001 — Existing account permission and custody inventory

**Observed:** 2026-11-07, through connected GitHub, Airtable and Dropbox account tools.
**Purpose:** record visible permission metadata and identify what remains unknown before any witness-provider choice.
**Scope:** read-only connector inspection. This is not a full account-security audit and does not establish recovery-path independence.

## 1. GitHub — Yggdrasil-Lean

- Repository: `mcoke9-lgtm/Yggdrasil-Lean`
- Repository visibility: public.
- Repository owner type: personal user account (`mcoke9-lgtm`), not an organization.
- Authenticated connected GitHub login: `mcoke9-lgtm`.
- Repository metadata reports the connected account's effective permissions as admin, maintain, pull, push and triage; admin is true.
- Connected GitHub app installation inventory showed the personal account `mcoke9-lgtm`; connected organization membership list was empty.
- No collaborator roster, branch-protection/ruleset configuration, account recovery methods, MFA enrollment, recovery codes, organization-level control, or signing-key custody was inspected. Available connector actions in this inspection did not expose a collaborator roster or branch-protection/ruleset read.
- Consequence: GitHub is a single personal-account administrative domain for this repository as far as the inspected metadata shows. GitHub history and CI artifacts must not be treated as the independent SCH-001 continuity witness.

## 2. Airtable — Adaptive Filesystem pilot

- Visible base: `Adaptive Filesystem — ChatGPT Pilot`.
- Base permission level returned for the connected account: `create`.
- No external integration accounts were listed for the connected Airtable user.
- The base-level permission response does not identify all collaborators, workspace owners, enterprise administrators, recovery contacts, automation/service credentials, or who can restore/delete/export the base.
- No attempt was made to enumerate unrelated bases or records.
- Consequence: this pilot base is not established as an independent authority store or witness. Its exact administrative and recovery domain remains unknown.

## 3. Dropbox

- Title-only searches for `SCH-001 scheduler witness` and `Adaptive Filesystem` returned zero matches.
- Root folder listing returned no entries through the connected Dropbox tool.
- This is evidence only about what the connected search/list operations returned at inspection time; it does not prove the Dropbox account is empty or that no content exists in inaccessible/team namespaces.
- No Dropbox team/admin console, member roster, sharing policy, recovery path, account ownership, backup/restore authority, or project-specific folder permissions were inspected.
- Consequence: Dropbox cannot currently be counted as a witness, backup custodian, or independent recovery domain.

## 4. Comparative result

| System | Observed access/control fact | Recovery/admin independence | SCH-001 witness suitability |
|---|---|---|---|
| GitHub | Personal repo; connected account has admin permission | Unknown; personal-account recovery not inspected | Not suitable as independent witness |
| Airtable pilot | Connected account has `create` base permission | Unknown; base/workspace administrators not inspected | Not established; keep disposable/pilot only |
| Dropbox | Searches empty; root listing empty | Unknown; team/account controls not inspected | Not established; no project evidence located |

## 5. Material unknowns

1. Who else can administer the GitHub account/repository, alter branch rules, manage app installations, or recover the account?
2. Is MFA enabled on GitHub, Airtable and Dropbox, and are recovery methods controlled independently?
3. Who owns/administers the Airtable workspace and pilot base, and who can restore or delete it?
4. Is Dropbox a personal or team account, and who controls its recovery and team-level administration?
5. Is there any already-existing independently administered service/account with transactional conditional append, immutable or independently detectable restore history, and separate recovery control?
6. Can a destination enforce a witnessed generation at its actual side-effect boundary?

These questions require owner-provided, redacted metadata or provider UI exports. Do not share passwords, API tokens, recovery codes, seed phrases or private keys in chat.

## 6. Decision and next gate

**Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.**

- No currently inspected system is demonstrated to be an independent witness.
- Do not infer independence from separate products, resources, API tokens or folders.
- No new cloud account, database, Worker, credential, key, app or service was created.
- No permission, sharing, recovery, MFA, retention, branch rule or deployment setting was changed.
- Next gate: obtain a redacted owner-confirmed map of account ownership, administrator/recovery principals, MFA/recovery control, restore/delete authority and audit/retention settings for any candidate system. Then compare candidate domains against the SCH-001 minimum separation criteria.

**Integrity:** facts above are limited to the returned connected-account metadata and read-only search/list results. No provider security review, live restore test, identity verification or independent custody audit was performed.
