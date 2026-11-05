# Builder App Adoption Checklist

**App / project:** [name]  
**Repository / branch:** [repository and branch]  
**Owner / decision authority:** [person or role]  
**Status:** Proposed / In progress / Tested / Verified / Adopted  
**Date:** [YYYY-MM-DD]

Use this checklist as a starting point. Tailor it to the platform, threat model, data sensitivity, and operational context. Mark items N/A with a reason; do not treat unchecked items as completed.

## 1. Production boundary

- [ ] Record the production application identity and current trusted version.
- [ ] Identify authoritative data stores, files, preferences, and external integrations.
- [ ] Identify production credentials and where they are managed (never record secret values here).
- [ ] List prohibited changes for this task (for example: migration, credential rotation, service changes).
- [ ] Record the rollback or return-to-known-good path.
- [ ] Confirm who can authorize consequential production changes.

## 2. Environment roles

| Role | Exists? | Identity / environment | Data class | Credentials |
|---|---|---|---|---|
| Production | [ ] | [stable identity] | [authoritative] | [production-managed] |
| CI | [ ] | [isolated identity/environment] | [synthetic/disposable] | [disposable/test] |
| Development | [ ] | [isolated identity/environment] | [synthetic/disposable] | [disposable/test] |

- [ ] Decide which roles need separate installable identities.
- [ ] If all three are installable, assign and verify three pairwise-distinct Android application IDs.
- [ ] Give each installable variant a unique, recognizable launcher label.
- [ ] Record the exact expected package IDs and labels for Production, CI, and Development before building.
- [ ] Audit merged manifests for duplicate provider authorities, custom permissions, exported component identities, deep links/app links, and other globally registered identifiers.
- [ ] Check shared/external storage paths, notification channels, scheduled-work identifiers, databases, backup/restore behavior, and any other cross-variant state.
- [ ] Define signing compatibility for each variant; never use CI/development signing credentials as production credentials.
- [ ] Verify built APK metadata and merged manifests, not just source declarations.
- [ ] Test all install orders and pairwise combinations; confirm installing, updating, or uninstalling one variant leaves the other two intact.
- [ ] Ensure CI and Development cannot accidentally overwrite production data.
- [ ] Ensure test credentials cannot access production resources.
- [ ] If a role is not installable, record that explicitly and do not claim three-way device coexistence.

## 3. Automated validation

- [ ] Unit tests and relevant integration tests.
- [ ] Static checks for permissions, network access, and secret leakage.
- [ ] Build from a recorded source revision and known toolchain.
- [ ] Inspect the built artifact rather than relying only on source configuration.
- [ ] Verify package/application identity and variant label.
- [ ] Verify signing and signer identity where applicable.
- [ ] Repeat the build and compare the properties that matter.
- [ ] Retain artifacts with a documented expiry and provenance.
- [ ] Record workflow/run links, commit SHA, artifact digest, and test scope.

## 4. Real-device / real-environment acceptance

- [ ] Install the intended artifact without disturbing the trusted production installation.
- [ ] Confirm its visible identity.
- [ ] Exercise the normal success path.
- [ ] Exercise cancellation and failure paths.
- [ ] Check permissions, data isolation, and external side effects.
- [ ] If three variants are intended to coexist, install all three together and test each install order, update-in-place, independent data, and independent uninstall.
- [ ] Confirm recovery/rollback behavior.
- [ ] Record user-confirmed observations separately from automated or independently verified evidence.

## 5. Data, credentials, and recovery

- [ ] Use only disposable data and credentials in CI/Development.
- [ ] Confirm no production secret is present in source, logs, artifacts, or chat.
- [ ] Define backup locations and independent failure domains.
- [ ] Perform restoration tests, not just backup creation.
- [ ] Document migration prerequisites and rollback.
- [ ] Obtain explicit approval before production credential provisioning, data migration, or irreversible changes.

## 6. Evidence register

| Claim / property | Status (proposed, tested, verified, user-confirmed, pending, blocked, not done) | Evidence / link | Scope and limitations |
|---|---|---|---|
| [claim] | [status] | [link or record] | [what this does and does not prove] |

## 7. Adoption decision

- [ ] App-specific risks and safety gates reviewed.
- [ ] Outstanding blockers listed with owners or next action.
- [ ] Development variant kept optional unless justified.
- [ ] Pattern changes proposed from lessons learned.
- [ ] Adoption decision recorded by the decision authority.

**Decision:** Not yet adopted / Adopted with exceptions / Adopted  
**Exceptions and rationale:** [details]  
**Next review checkpoint:** [event or date]

## Integrity check

1. Exact count: all six sections reviewed.
2. Role mapping: Production, CI, and Development each have an explicit status.
3. Omissions and duplication: N/A items are justified; no duplicated authority or data path is hidden.
4. Evidence: proposed, tested, verified, user-confirmed, pending, blocked, and not-done states are not conflated.
5. Correction: discrepancies and failed checks have a recorded resolution or owner.
6. Persistence: repository path, commit, evidence links, and unresolved uncertainty are recorded.
