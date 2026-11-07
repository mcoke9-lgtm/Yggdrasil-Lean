# SCH-001 GitHub Repository Evidence-Custody Boundary Audit

Date: 2026-11-07
Status: READ-ONLY REPOSITORY CONFIGURATION OBSERVATION — NOT AN INDEPENDENT SECURITY AUDIT

## 1. Finding

The SCH-001 documentation branch and repository default branch are both currently reported by GitHub as unprotected. The latest SCH-001 branch commit and the current default-branch tip are reported as unsigned.

This means GitHub is useful for versioned documentation and source review, but the current repository configuration does not provide enforced branch protection or signed-commit verification for this evidence chain. It must not be described as an independent custody anchor.

## 2. Direct observations

Observed through the connected GitHub read-only fetch interface on 2026-11-07:

| Item | Observation |
|---|---|
| Repository | `mcoke9-lgtm/Yggdrasil-Lean` |
| Visibility | Public |
| Default branch | `main` |
| SCH-001 branch | `sch-001-handoff-inbox-001` |
| SCH-001 branch protection | `protected: false` |
| SCH-001 branch head | `7b9c352a93df90d7a7879d13dd703f61a103f9b0` |
| SCH-001 head commit signature | `verified: false`, reason: `unsigned` |
| Default branch protection | `protected: false` |
| Default branch head | `4097f38839cd4422e6038f00979232d627db07ba` |
| Default branch head signature | `verified: false`, reason: `unsigned` |
| Required status checks on inspected branches | Enforcement off; no required contexts/checks returned |

The SCH-001 branch head commit is the evidence-chain-index commit. Its parent is the owner-attestation-verification-protocol commit. GitHub returned the branch head and protection metadata directly.

## 3. What this establishes — and what it does not

Established:
- Both inspected branches are reported unprotected at observation time.
- The inspected tip commits are unsigned according to GitHub's commit-verification field.
- No required status-check enforcement was returned for either inspected branch.
- The repository is public, so its contents are publicly readable.

Not established:
- Whether any repository rulesets, organization-wide policies or controls outside the returned branch endpoint apply.
- Whether all collaborator, token, deployment-key, GitHub App and recovery authorities have been enumerated.
- Whether GitHub retains immutable audit evidence outside the repository administrator's control.
- Whether an external signing or archival system exists.
- Whether any particular commit content is false or malicious.
- Whether the account has hardware-backed MFA or recovery separation.

This is a narrow configuration observation, not a claim that GitHub offers no security controls. It records only what was directly returned by the inspected endpoints.

## 4. SCH-001 consequences

1. Keep GitHub in the source-history, review-artifact and CI-evidence role.
2. Do not treat repository commits, branch names, GitHub Actions runs or blob SHAs as proof of independent witness custody.
3. Do not infer that a commit is independently authenticated because its SHA is stable.
4. If stronger repository integrity is later desired, propose a separate hardening plan: protected branches/rulesets, required independent review, signed commits/tags, restricted workflow permissions, protected environments and external evidence archival.
5. Repository hardening alone would still not qualify GitHub as an independent witness if the same account owner or recovery domain controls both the project and its purported anchor.
6. Do not change branch protection, signing configuration, access or workflow permissions as part of this audit. Such changes require separate explicit authorization and a review of potential workflow disruption.

## 5. Evidence classification

- Repository visibility/default branch: OBSERVED from repository metadata.
- SCH-001 branch protection: OBSERVED from branch endpoint.
- Main branch protection: OBSERVED from branch endpoint.
- Commit signature status: OBSERVED from GitHub commit metadata.
- Effective administrator/recovery roster: UNKNOWN.
- Rulesets beyond branch endpoint response: UNKNOWN.
- Independent custody: NOT ESTABLISHED.
- External audit archive: UNKNOWN.
- Changes made: NONE.

## 6. Integrity boundary

This was read-only inspection. No settings, branches, files, permissions, credentials, workflows or tasks were changed. The observations are time-bound and should be rechecked before relying on them.
