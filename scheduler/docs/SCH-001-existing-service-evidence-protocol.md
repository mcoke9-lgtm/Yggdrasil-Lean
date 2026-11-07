# SCH-001 Existing-Service Evidence Acquisition Protocol

Status: PROPOSED
Decision: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE
Purpose: Define the minimum non-secret evidence needed to evaluate an already-existing service as a possible independent scheduler witness. This protocol does not authorize provisioning, account changes, credential sharing, or task transfer.

## 1. Current evidence boundary

The connected read-only inspections have established:
- GitHub: the connected personal account has admin permission on Yggdrasil-Lean. Full collaborator roster, account recovery controls, MFA, branch rules, and secret/deployment credential custody remain unverified.
- Airtable: the connected account is owner of the only accessible workspace returned, My First Workspace. Full member roster, recovery control, and platform-level administrative paths remain unverified.
- Dropbox: the connected identity is a personal account. Recovery controls, MFA, account recovery authority, and any separately held custodian arrangement remain unverified.
- Cloudflare D1: a local/workerd compatibility prototype passed 21/21 tests, but no live account/database/Worker or independent account boundary has been established.

These observations do not establish that other controls or services are absent. They establish only what was visible through the connected tools and inspected artifacts.

## 2. Evidence collection rules

1. Use only an already-existing service. Do not create a new account or resource to complete this protocol.
2. Do not send passwords, API keys, access tokens, recovery codes, private keys, seed phrases, or full credential screenshots in chat.
3. Prefer redacted screenshots or exported role/policy summaries that show role names, scope, and effective authority while hiding identifiers and secrets.
4. Record who supplied each item, when it was observed, the service/account scope, and whether it is provider-generated, owner-attested, or independently tested.
5. A statement by the same administrator whose independence is being assessed is owner-attestation, not independent verification.
6. If a control cannot be inspected, record UNKNOWN / NOT EXPOSED. Do not infer absence.
7. Evidence must be current enough to describe the present administrative and recovery configuration; record its observation date.

## 3. Minimum evidence bundle

| Bundle item | Evidence sought | Safe evidence form | Gate(s) |
|---|---|---|---|
| E1 Account boundary | Which existing account, organization, workspace, or tenant owns the service? | Redacted account/tenant overview; provider-generated account type and ownership metadata. | G1, G2 |
| E2 Effective administrators | Who can administer, delete, export, restore, change roles, or transfer ownership? | Redacted role roster with role scope and permission descriptions. | G1, G2 |
| E3 Recovery authority | Who can reset the owner/admin identity, recover the account, or restore service state? | Recovery-policy summary and role/process description; no recovery codes or private contact details. | G2, G10 |
| E4 Identity and credential separation | Can source/CI administrators obtain witness write, signing, or recovery credentials? | Redacted identity-provider policy, service-principal inventory, credential access policy. | G1, G3 |
| E5 Transaction primitive | Does the service support a genuinely atomic conditional append/CAS with failure abort? | Provider documentation plus a test plan; later, a controlled test using non-production data. | G5 |
| E6 Continuity and restore | Can an administrator restore or replace both current state and its only continuity evidence? | Backup/restore policy, audit-retention description, independent checkpoint ownership. | G4, G10 |
| E7 Reviewer access | Can an independent reviewer directly inspect complete evidence with separately issued read-only credentials? | Redacted role grant and sample evidence export/read-back procedure. | G7 |
| E8 Destination enforcement | Can every protected side effect reject a stale generation atomically? | Destination interface contract and a non-production stale-generation test plan. | G8 |
| E9 Failure handling | What happens on outage, timeout, uncertain append, stale state, and contradictory receipts? | Failure matrix and controlled non-production test results. | G9 |
| E10 Key custody and succession | Who can rotate keys, replace custodians, reset baselines, or migrate accounts? | Role separation and dual-control procedure; no key material. | G3, G6, G10 |

## 4. Evidence quality labels

- PROVIDER METADATA: returned directly by the provider or its authenticated API.
- OWNER ATTESTATION: statement or screenshot supplied by an account owner; useful but not independent.
- CONFIGURATION EVIDENCE: redacted policy or role configuration showing effective permissions.
- REPRODUCIBLE TEST: repeatable test with inputs, outputs, environment, and result.
- INDEPENDENT REVIEW: confirmation by a reviewer who is not an administrator of the source or witness.
- NOT EXPOSED: the connected tool does not provide this information.
- UNKNOWN: no reliable evidence currently available.

No single evidence type automatically proves a gate. A provider help page describing a feature is not proof that the feature is enabled or correctly configured in the user's account.

## 5. Safe evaluation sequence

1. Identify an existing candidate and its exact account boundary.
2. Map effective administrator, recovery, identity-provider, CI, and credential control.
3. Reject immediately if a common authority can silently rewrite both source and witness without independent detection.
4. Verify that the candidate's actual service supports atomic conditional append and fail-closed stale-version handling.
5. Establish a continuity record outside the candidate's own restore/admin domain.
6. Define separately authenticated reviewer access.
7. Test rollback, restore, lost-response same-ID retry, competing append, witness outage, and stale destination generation using non-production data.
8. Rehearse key rotation and recovery without resetting monotonic history.
9. Re-score all ten hard gates. UNKNOWN remains blocking.
10. Submit the evidence package for independent review before any adoption decision.

## 6. Stop conditions

Stop and retain WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE if:
- The only available evidence is supplied by the same administrator whose independence is under review.
- A common account, identity provider, recovery channel, CI runner, organization super-admin, or backup operator can silently control source and witness together.
- Conditional append semantics are described but not demonstrated.
- Restore can rewind the witness and its only continuity record together.
- Reviewer access depends on source-controlled summaries.
- Any protected side effect can proceed with stale or unknown authority.
- The append outcome is uncertain and cannot be reconciled using the original stable transfer ID.

## 7. What this protocol does not authorize

It does not authorize sharing credentials, changing roles, enabling MFA, creating accounts, provisioning databases or Workers, deploying code, changing phone settings, installing software, activating tasks, or transferring scheduler authority.

## 8. Current disposition

No already-inspected service has a complete evidence bundle. No independent witness is adopted. This document is a proposed collection protocol, not an assertion that the user must supply every item immediately.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.
