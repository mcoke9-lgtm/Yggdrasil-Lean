# Builder App Development Pattern

**Status:** Proposed reusable pattern; first case study is Builder Key Steward. The pattern is not yet validated across multiple applications.
**First implementation:** Builder Key Steward in `key-steward/`, branch `builder-key-steward-foundation-001`.
**Design principle:** Develop and test an application without putting its trusted production installation, data, credentials, or recovery path at risk.

## 1. Three implementation roles

A Builder application may have three deliberately separated implementations. They are roles, not a requirement to maintain three copies of every source file.

| Role | Purpose | Identity and data | Lifecycle |
|---|---|---|---|
| **Production** | Trusted user-facing operation | Stable application ID; authoritative user data and production credentials | Preserved and changed only through reviewed releases |
| **CI** | Automated, repeatable verification and disposable test operation | Distinct application ID where installable; disposable credentials and test data | Long-lived project member; safe to rebuild and replace |
| **Development** | Feature work, experiments, diagnostics, and prototypes | Separate application ID when side-by-side installation is useful; synthetic or disposable data | Evolves rapidly; never treated as authoritative |

Not every app needs all three installed on a device. CI may run only in a hosted build environment. Development may be a build variant rather than a separately published app. The separation must be appropriate to the platform and threat model. If all three are intended to be installed together, three-way coexistence is a release gate, not an assumption.

### Builder Key Steward example

- Production/reserved application ID: `org.yggdrasil.keysteward`, launcher label **Builder Key Steward**.
- CI application ID: `org.yggdrasil.keysteward.ci`, launcher label **Builder Key Steward CI**.
- The CI build uses a disposable PKCS#12 identity for signing tests. It is not a production key.
- Development application ID: `org.yggdrasil.keysteward.dev`, launcher label **Builder Key Steward Development**. Its Gradle build type and manifest overlay have now been added; CI validation is pending.
- The CI package is intended to coexist with the existing production installation. It must not update, migrate, or replace it.
- The source configuration now defines three distinct identities: Production/reserved (`org.yggdrasil.keysteward`), CI (`org.yggdrasil.keysteward.ci`), and Development (`org.yggdrasil.keysteward.dev`). CI is being extended to build and inspect both non-production APKs. Three-way device coexistence remains unverified until all three are installed and tested together.

## 2. Six design rules

### 2.1 Identity separation

Give independently installable variants distinct application identities and recognizable names. Verify the built artifact's application ID and visible label; do not rely only on Gradle configuration or source declarations.

**Three-install conflict gate (when Production, CI, and Development must coexist):**

- Assign three pairwise-distinct Android `applicationId` values. A different launcher label, module name, Gradle project name, or source namespace alone does not prevent Android from treating two APKs as the same installed application.
- Give each install a distinct, human-recognizable launcher label and verify the label in the built APK and on the device.
- Audit manifest-level collision points across all three variants: `ContentProvider` authorities, custom permission names, exported component identities, intent/deep-link filters, app links, account/authenticator or sync-adapter authorities, and any other globally registered Android identifiers. Parameterize authorities/names per variant where they must be unique.
- Check shared/external storage paths, notification channels, scheduled work identifiers, database names, file names, and backup/restore behavior for cross-variant interference. Keep private app data separate by default; do not introduce shared state without an explicit design.
- Define signing compatibility deliberately. Distinct package IDs allow side-by-side installs with different signing certificates; updates to the same package must use an accepted signing lineage. Never use a CI/development signer as a production signer.
- Verify the final merged manifests and built APK metadata, not just source manifests or Gradle declarations.
- Test all three install orders and pairwise combinations on a clean test device. Confirm install, launch, data isolation, update-in-place for each variant, and uninstall of one variant without damaging the other two. Also test that installing/updating one does not silently replace another.
- If any role is not meant to be installed, record it as a non-installable build/test environment and exclude it from the three-way device-install claim. Do not claim three-way coexistence until all three installable artifacts have passed.

### 2.2 Data separation

Use synthetic or disposable data in CI and Development. Prevent test operations from mutating production databases, files, preferences, or device state. Define explicit migration paths rather than treating shared storage as harmless.

### 2.3 Credential separation

Use disposable credentials in CI and Development. Never copy production secrets into test environments, source control, logs, build artifacts, or chat. Production credentials require a separate, reviewed provisioning process and least-privilege access.

### 2.4 Repeatable assembly

Build from a known source revision with pinned toolchain/dependency versions where practical. Rebuild and compare the properties that matter: package identity, test results, signature validity, signer fingerprint, and artifact provenance. Byte-for-byte APK equality is not assumed unless the build is designed to be reproducible at that level.

### 2.5 Layered acceptance

Combine automated unit/integration checks with artifact inspection and real-device or real-environment acceptance. Keep the evidence classes distinct:
- **Proposed:** design intent not yet exercised.
- **Tested:** a test or check ran; record its scope and result.
- **Verified:** independent evidence supports the stated property.
- **User-confirmed:** the user reports the real-world result; do not describe it as independently observed.
- **Pending / blocked / not done:** retain these states instead of converting them into success.

### 2.6 Recoverability

Before consequential changes, establish backups, restoration tests, migration boundaries, and a known-good rollback route. Two copies count as independent backups only when their failure modes and retention locations are meaningfully independent. Never remove the prior working installation as a shortcut around a failed update or signature mismatch.

## 3. Recommended development sequence

1. **Define the production boundary:** identify authoritative app identity, data, credentials, integrations, and the changes that are out of scope.
2. **Create an isolated CI path:** separate package identity when needed, disposable test data, disposable credentials, and restricted permissions.
3. **Automate checks:** tests, static safety checks, build, artifact inspection, signature/fingerprint checks where relevant, and artifact retention.
4. **Add a Development path only when useful:** keep experiments isolated; promote changes through review and CI rather than copying experimental state into production.
5. **Perform real-environment acceptance:** install the intended artifact, confirm the visible identity, test normal and negative paths, and record user-confirmed observations separately. If three variants are intended to coexist, execute the full three-way conflict gate above.
6. **Review recovery before release or migration:** verify backups and restoration, document rollback, and obtain explicit approval for production credential or data changes.

## 4. Security and authority boundaries

- CI success does not prove device acceptance, operational suitability, or security audit completion.
- A test signing identity is not a production identity.
- Never create or rotate production credentials as an incidental part of a foundation or CI change.
- Do not silently migrate, overwrite, uninstall, or disable an existing trusted installation.
- Keep production services, schedulers, and host integrations outside the change unless a separately reviewed task explicitly includes them.
- Human approval remains required for consequential production changes, external communication, data migration, and credential provisioning.
- A successful build artifact is evidence about that build, not a guarantee about every later build or environment.

## 5. First case study: Builder Key Steward

The current Builder Key Steward work demonstrates several parts of this pattern:

- A separate CI package identity and visible launcher label have been added.
- The Android CI workflow completed successfully on commit `49540efea9c49c5a3047bde577036e347913c524` in both the push run [42020817240](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42020817240) and pull-request run [42020825281](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42020825281).
- Earlier workflow checks exercised disposable PKCS#12 generation, tests, APK signing, repeat assembly, signature verification, signer fingerprint comparison, and artifact upload. The corrected-label runs also passed; inspect their job steps and artifact before making claims about the exact contents of the newest artifact.
- The user reported successful installation of the CI app and successful disposable-keystore creation, save, close, and reopen. These are user-confirmed observations, not independent device telemetry.
- The user reported that the two app installations were difficult to distinguish and that the export process was unclear. The CI launcher label addresses the first issue. The export flow still requires clearer user-facing explanation and physical cancellation/clipboard acceptance.
- Export-cancellation and clipboard-clear rehearsals remain pending. Two independently retained encrypted backups, production-key creation, formal security review, and production migration remain not done and gated.
- A separate Development build type and manifest label have been added. Its successful build, artifact identity, and physical coexistence with Production and CI remain pending evidence.
- Three-way installation conflict testing is **not done**. CI and Development have distinct configured package identities; the updated workflow must pass artifact inspection, and all three variants must then pass physical install-order, coexistence, update, data-isolation, and independent-uninstall tests. Do not interpret source configuration or CI success as proof of device coexistence.

This case study supports the value of identity separation and automated build validation. It does not yet establish that the full pattern is proven or sufficient for other applications.

## 6. Adoption and revision criteria

Treat this document as a reusable starting standard, not a rigid architecture. Before adopting it for another app:
- map the three roles to that app's actual platform, identity, data, and deployment model;
- identify which roles need distinct installable identities and which can remain build/test environments;
- define app-specific safety gates and recovery requirements;
- record the first real-world acceptance results; and
- revise the pattern when a second application reveals missing or unnecessary rules.

**Integrity checkpoint:** three roles are defined; six rules are present; three-way install collision checks are an explicit gate when all roles must coexist; proposed design is distinguished from tested and user-confirmed evidence; no production credential or migration work is authorized; cross-application validation remains open.
