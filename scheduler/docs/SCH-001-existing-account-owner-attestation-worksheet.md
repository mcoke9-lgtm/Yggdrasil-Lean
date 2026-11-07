# SCH-001 Existing-Account Owner Attestation and Evidence Review Worksheet

Date: 2026-11-07
Status: PROPOSED TEMPLATE — NOT AN ATTESTATION; NO OWNER RESPONSE RECEIVED

## Purpose

This worksheet defines the minimum non-secret information needed to assess whether an already-existing service account could be separated from scheduler/executor authority and whether an independent continuity anchor is feasible.

It is not a request to disclose credentials. It does not authorize account changes, project creation, permission changes, deployment, testing or adoption.

## Instructions for the account owner

Complete only what you can verify. Use role categories and counts where possible; do not include names, email addresses, account IDs, organization IDs, URLs containing tokens, or other identifying details unless a secure review process specifically requires them.

For each answer, label it:
- VERIFIED — directly observed in current provider settings or authoritative documentation;
- OWNER-ATTESTED — based on the owner's knowledge but not independently inspected;
- UNKNOWN — not established;
- NOT APPLICABLE — explain why.

Record the observation date and evidence type. Do not attach passwords, API tokens, recovery codes, private keys, seed phrases, session cookies, or security-question answers.

## Part A — account identity and ownership

| Prompt | Owner response |
|---|---|
| Service/provider | UNKNOWN |
| Account type: personal / team / organization / other | UNKNOWN |
| Is this an already-existing account? | UNKNOWN |
| Is the account owner-controlled? | UNKNOWN |
| Is there a provider organization or parent account above it? | UNKNOWN |
| Observation date and evidence type | UNKNOWN |

## Part B — effective administrative authority

Count roles, including inherited roles and parent-organization administrators.

| Prompt | Owner response |
|---|---|
| Number of effective account/org administrators | UNKNOWN |
| Can any administrator add/remove other administrators? | UNKNOWN |
| Can provider support override, recover or transfer control? | UNKNOWN |
| Can billing owner or payment administrator change ownership/access? | UNKNOWN |
| Can any administrator delete, restore, export or replace the witness data? | UNKNOWN |
| Are there service principals, automation identities or deploy tokens with administrative scope? | UNKNOWN |

Do not infer separation from different usernames or different API tokens if both are controlled by the same effective administrator.

## Part C — account recovery and continuity

| Prompt | Owner response |
|---|---|
| Who can reset MFA or recover the account? | UNKNOWN |
| Is recovery controlled by the same person/team that operates the scheduler or executor? | UNKNOWN |
| Can support or billing recovery bypass normal administrator separation? | UNKNOWN |
| Can a deleted or suspended account be reclaimed or restored? By whom? | UNKNOWN |
| Can backups, snapshots or point-in-time restore lower or replace witness history? | UNKNOWN |
| Is an independent continuity anchor held under a different owner, administrator and recovery path? | UNKNOWN |

A second database, project, branch, bucket or storage location is not independent if the same effective administrator or recovery process controls both.

## Part D — scheduler/executor separation

| Prompt | Owner response |
|---|---|
| Can scheduler identities administer the witness account? | UNKNOWN |
| Can executor identities administer the witness account? | UNKNOWN |
| Can scheduler/executor identities alter, delete, restore or rewrite witness records? | UNKNOWN |
| Can scheduler/executor identities rotate or replace witness signing keys? | UNKNOWN |
| Can scheduler/executor identities alter the trusted baseline or continuity anchor? | UNKNOWN |
| Are credentials scoped, revocable and independently audited? | UNKNOWN |

## Part E — signing-key and reviewer boundaries

| Prompt | Owner response |
|---|---|
| Where are witness signing keys held (describe class of service only)? | UNKNOWN |
| Who can use, export, replace or revoke those keys? | UNKNOWN |
| Are signing keys unavailable to scheduler/executor administrators? | UNKNOWN |
| Is key rotation dual-controlled and independently recorded? | UNKNOWN |
| Can a reviewer inspect evidence using separately issued credentials? | UNKNOWN |
| Can the reviewer independently authenticate receipts and reproduce tests? | UNKNOWN |

Never provide key material or recovery secrets in this worksheet.

## Part F — transaction and destination evidence

Complete only if actual tests have already been performed or are separately authorized.

| Test/evidence | Result |
|---|---|
| Concurrent append with identical expected generation: exactly one winner | NOT TESTED |
| Failed conditional guard aborts the whole transaction | NOT TESTED |
| Restart/replay preserves monotonic generation | NOT TESTED |
| Restore/rollback is detected by an anchor outside the provider admin domain | NOT TESTED |
| Quota exhaustion, timeout and provider outage produce UNKNOWN/block | NOT TESTED |
| Destination rejects stale generation atomically at side-effect commit | NOT TESTED |
| Independent reviewer reproduces results | NOT TESTED |

A local/workerd test is not a live-provider test. A scheduler preflight check is not destination-side enforcement.

## Part G — evidence references and limitations

For each claim, identify the evidence class without including sensitive contents:
- Provider settings directly observed;
- Provider documentation;
- Owner attestation;
- Reproducible test report;
- Independent reviewer receipt;
- Not available.

Record:
- Observation date;
- Who or what role performed the observation (role label only);
- Scope inspected;
- Scope not inspected;
- Any conflicting or contradictory evidence;
- Expiry/review date if the evidence can become stale.

## Reviewer decision

The reviewer must assign one of:
- BLOCKED — a hard gate is failed;
- UNKNOWN — evidence is missing, stale, contradictory or not independently verifiable;
- CANDIDATE_FOR_ISOLATED_TEST — preliminary separation appears feasible, but technical gates remain;
- QUALIFIED_FOR_ADOPTION_REVIEW — every hard gate is independently VERIFIED; this is not itself adoption.

No weighted score can compensate for a failed or UNKNOWN hard gate. The reviewer must state evidence for every gate and identify common administrators, recovery paths, billing authority, support escalation and key custody.

## Stop rules

Retain UNKNOWN and stop if:
- owner or effective admin set cannot be established;
- account recovery can collapse the claimed separation;
- the same authority can rewrite both witness state and its continuity anchor;
- scheduler/executor can administer or sign witness state;
- restore can lower generation without an independent alarm;
- destination cannot enforce current generation at the side-effect boundary;
- independent review cannot be performed.

## Current project state

- Account owner attestation: NOT RECEIVED.
- Existing Cloudflare account availability: NOT ESTABLISHED.
- Neon account availability: NOT ESTABLISHED.
- Supabase account availability: NOT ESTABLISHED.
- Live provider tests: NONE.
- New accounts/projects/resources: NONE.
- Credential or permission changes: NONE.
- Adoption: NONE.
- Active decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## Integrity boundary

This worksheet is a proposed evidence collection template only. It does not establish any provider capability or user account fact. It does not request secrets and does not authorize external changes.
