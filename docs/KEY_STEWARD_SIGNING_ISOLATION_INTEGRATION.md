# Key Steward Signing Isolation Integration

**Status:** Proposed integration branch; not yet tested by CI or adopted.
**Source branch:** `builder-key-steward-foundation-001` / PR #9.
**Target baseline:** `independent-builder-runtime-foundation-001`.

## Scope of this port

This branch preserves the current primary-branch Key Steward implementation and adds only the package/build isolation and CI artifact checks needed to prevent a CI or Development APK from replacing the existing Production package.

- Production identity remains `org.yggdrasil.keysteward`.
- CI identity is `org.yggdrasil.keysteward.ci`.
- Development identity is `org.yggdrasil.keysteward.dev`.
- Production release assembly is unsigned in CI and is not an installable release.
- CI and Development use the Android debug signing identity generated for that CI run. The workflow checks repeat-build signer consistency within that run only.
- No permanent signing key, GitHub secret, app data, installed app, or production signing configuration is changed.

## Deliberate exclusions from PR #9

PR #9's alternative `KeyStoreEngine` and replacement `MainActivity` were not copied over the current primary-branch `KeystoreMaterial` / `MainActivity`. They are materially different implementations and replacing the current files would risk changing the user's established workflow and signing alias. Their additional test ideas remain candidates for a separately reviewed enhancement.

## Acceptance limits

CI success can establish package IDs, labels, current merged-manifest checks, offline permission state, unit-test results, APK signature validity, and repeat-build signer equality within one runner session. It cannot establish:

- cross-run signing continuity for ephemeral debug builds;
- compatibility with the signer on an already-installed device app;
- three-way coexistence on the user's phone;
- backup restoration or production migration safety.

No production migration is authorized by this branch. Before any same-package update, compare the installed app certificate fingerprint with the candidate APK fingerprint. If they differ, stop. Preserve the existing app and data until a reviewed migration and recovery plan is complete.

## Evidence states

- **Proposed:** this branch's changes pending CI.
- **Previously tested on PR #9:** alternative implementation's CI checks and user-reported disposable keystore rehearsal; not evidence that this exact integration branch passes.
- **Unknown:** installed APK signer fingerprints and three-way device coexistence.
