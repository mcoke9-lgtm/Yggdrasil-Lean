# Android Package Identity and Signing Audit 001

**Date:** 2026-11-05  
**Repository:** `mcoke9-lgtm/Yggdrasil-Lean`  
**Branch:** `independent-builder-runtime-foundation-001`  
**Status:** Source-level root cause identified; on-device certificate comparison pending.

## Executive finding

The repeated Android message, "App not installed as package conflicts with an existing package," is consistent with an update being signed by a different certificate from the already-installed package.

The repository contains a common signing-process defect across the two applications:

- Builder Host has a persistent signing configuration, but it is conditional on four GitHub Actions secrets. When they are absent, Gradle uses the default debug signing identity. CI explicitly skips distributing the Builder Host APK when persistent signing is absent, but locally built debug APKs can still be produced.
- Key Steward has no persistent signing configuration. Its CI job builds and uploads the default debug APK on each run. GitHub-hosted runners are ephemeral; their default debug keystores are not a stable release identity.

This is a strong source-level explanation for why successive builds can fail to update earlier installations. It is not yet a certificate-level proof of the exact Key Steward installation failure.

## Package identities

| Application | Gradle applicationId | Namespace |
|---|---|---|
| Builder Host | `org.yggdrasil.builderhost` | `org.yggdrasil.scheduler` |
| Key Steward | `org.yggdrasil.keysteward` | `org.yggdrasil.keysteward` |

These package IDs are distinct. The two applications do not conflict with each other merely because both are installed. Android's message indicates a conflict with an already-installed package identity or signing relationship; exact installed package state still needs on-device inspection.

## Evidence inspected

- `app/build.gradle`: Builder Host persistent signing requires `BUILDER_KEYSTORE_PATH`, `BUILDER_KEYSTORE_PASSWORD`, `BUILDER_KEY_ALIAS`, and `BUILDER_KEY_PASSWORD`. The debug build type selects the persistent configuration only when all four environment variables are present.
- `key-steward/build.gradle`: no signing configuration is declared; application ID is `org.yggdrasil.keysteward`.
- `key-steward/src/main/AndroidManifest.xml`: standalone launcher application; no custom shared UID.
- `.github/workflows/independent-builder-runtime.yml`: Key Steward CI builds and uploads `key-steward-debug.apk` without a persistent keystore. Builder Host artifact upload is conditional on persistent signing secrets.
- User screenshot dated 2026-11-05 shows multiple Key Steward APKs downloaded and Android's package-conflict installation failure for "Builder Key Steward".
- Existing Builder Host recovery record associates a previously installed build with debug certificate SHA-256 `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A`. This is the best available evidence for that historical Builder Host build, not direct live-device inspection.
- Key Steward artifact from CI run `42054633700` has APK SHA-256 `7cafe494514f89a13e89c8d79b65a4ab3b6753ac9aca8eb840e1c9618a15a813`; its signer fingerprint has not yet been captured in this audit.

## Confirmed vs inferred

**Confirmed**
- The user reports earlier installations of both applications.
- The user screenshot shows the current Key Steward package-conflict message.
- Builder Host and Key Steward have distinct application IDs.
- Key Steward has no stable signing configuration in source.
- Builder Host's stable signing configuration is conditional and the required secrets have not been configured.
- The current Key Steward CI workflow distributes an APK built with default debug signing.

**Strong inference, pending certificate comparison**
- A later debug APK was signed with a different certificate from the installed copy, causing Android to reject it as an update.

**Unknown**
- The signer certificate fingerprint of the currently installed Key Steward.
- The signer fingerprint of every earlier Key Steward APK the user installed.
- Whether the current downloaded APK and installed package differ only by signing certificate or also by version / package metadata.
- Direct live-device package state.

## Safe next steps

1. Stop installing successive debug APKs.
2. Do not uninstall either application, clear data, or use package-manager bypasses.
3. Obtain signer SHA-256 fingerprints for the installed-version source APK (if available) and each candidate replacement APK.
4. If the installed signer private key is unavailable, treat any change to a new permanent signer as a controlled migration, not an in-place update.
5. Before migration, export and independently verify each app's user data / journal and test restoration.
6. Establish a stable signing identity for every distributable application. CI must fail closed or mark output as non-distributable when stable signing is not configured. Do not upload temporary debug-signed APKs under names that imply they are update-ready.
7. Verify two independent builds with the same keystore produce the same certificate fingerprint before distributing updates.

## Safety boundary

No application was uninstalled, updated, cleared, or otherwise changed by this audit. No signing secrets or private key material were accessed. No device-side certificate comparison has yet been performed.


## Monitoring refresh — 2026-11-06

This refresh records read-only source, workflow, run, and APK-artifact inspection. No signing configuration, workflow, secret, release, application code, or device state was changed.

### Current primary-branch source check

- Current branch head observed through the open runtime PR: `dd9054ff942fb68f16c89ea7abcdc74f3eb2bbce`.
- `app/build.gradle` still declares Builder Host package `org.yggdrasil.builderhost`, versionCode 2 / versionName `0.2.0`, and selects PKCS#12 `builderCi` signing only when all four `BUILDER_*` environment values are present.
- `.github/workflows/independent-builder-runtime.yml` still requires the four repository secrets, rejects partial configuration, verifies the APK signature, and gates Builder Host artifact upload. Its current source compares against the historical `91:C4:...` certificate value. That value is evidence for the historical APK below only; it is not verified live-device evidence and must not be described as the currently installed signer.
- The latest directly observed run of that guarded workflow, run [42051226557](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42051226557) on commit `60184a4087576ff356151981c3b3e18d3bcd88cd`, reported persistent signing not configured, printed runner debug signer `D6:09:10:48:...`, and skipped artifact upload. Current secret settings cannot be independently read from this integration.
- A separate workflow named **Android Scheduler Host** nevertheless uploaded `app-debug.apk` in runs [42057541299](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42057541299) and [42078376339](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42078376339). Both artifacts are default/debug-signed and have different certificate fingerprints. The latest run's job explicitly completed an `Upload debug APK` step. Its workflow source file path was not exposed by the available read interface.
- Primary-branch `key-steward/build.gradle` still has no persistent signing configuration. The `key-steward-build` job in `independent-builder-runtime.yml` still assembles and uploads the production-ID debug APK without a persistent signer.
- Primary-branch `settings.gradle` currently includes only `:app` and `:key-steward`. The additional diagnostic modules below remain on separate draft branches.

### Retrieved and inspected APK ledger

Certificate fingerprints below are SHA-256 over the APK signer certificate DER. APK hashes are SHA-256 over the exact extracted APK bytes. Version fields come from each APK's binary manifest.

| Package ID | Run / artifact / source commit | Version | APK SHA-256 | Signer certificate SHA-256 | Evidence |
|---|---|---|---|---|---|
| `org.yggdrasil.builderhost` | Run 42005258590 / artifact 13403234570 / commit `0a23267d...` | 1 / `0.1.0` | `0e1aedfc8773645e5a3c929eb80fa64f481da77d8a0b50e3505dc9654f4e5c0c` | `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A` | Historical APK only; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42005258590) |
| `org.yggdrasil.builderhost` | Run 42057541299 / artifact 13428719173 / commit `0a0ca6c7...` | 2 / `0.2.0` | `64be5cf17c2f5b7cbe819a286748e530a0014dd1c2f3ee56a29d5cc52e221ad6` | `A7:9B:7A:AB:BE:3F:6D:4F:D2:99:4E:76:C5:90:9C:7C:C5:78:6D:06:FD:67:6B:35:CA:2D:E7:D1:04:A0:58:21` | Debug-signed upload; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42057541299) |
| `org.yggdrasil.builderhost` | Run 42078376339 / artifact 13439717163 / commit `dd9054ff...` | 2 / `0.2.0` | `499f3a8de1d3a4b37cc4616bc310f729315dea05f94cca24928336c6ea70a7d7` | `82:F0:CE:F0:D2:35:83:54:9D:4D:EB:98:AA:72:5D:B8:9C:F5:CD:8D:A8:CB:00:51:F2:F2:C9:13:28:7A:D9:4C` | Latest observed debug-signed upload; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42078376339) |
| `org.yggdrasil.keysteward` | Run 42054633700 / artifact 13429485693 / commit `f285fb98...` | 1 / `1.0.0` | `7cafe494514f89a13e89c8d79b65a4ab3b6753ac9aca8eb840e1c9618a15a813` | `32:53:A6:EC:0D:38:28:66:10:1B:AA:39:03:B8:A1:DF:F0:FD:6C:80:D2:79:1E:A4:24:7C:19:68:3E:E9:44:79` | Primary-branch debug APK; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42054633700) |
| `org.yggdrasil.keysteward` | Run 42017725787 / artifact 13410910197 / commit `4139407a...` | 1 / `0.1.0` | `69d6894e651be7c3d8f31bd51e4f016182e502944058e4710598484731fcaa70` | `6C:33:5C:73:A9:FF:60:11:6C:9F:0E:94:E2:99:E6:9C:FB:6F:60:27:35:BA:CE:8C:9D:D8:02:4F:48:D6:F8:F9` | PR #9 pre-isolation CI APK; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42017725787) |
| `org.yggdrasil.keysteward` | Run 42017732234 / artifact 13410745671 / commit `4139407a...` | 1 / `0.1.0` | `087a8af2517d3a29c94a1cd7f9ae97e9aaeddcaf79e8c6d62dd034199e40736e` | `15:55:55:6F:58:CF:9D:03:47:2E:68:DF:CD:F4:BF:D2:EF:49:BF:24:C7:14:27:43:96:39:95:9D:8C:42:D0:66` | PR #9 pre-isolation CI APK; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42017732234) |
| `org.yggdrasil.keysteward` | Run 42018167407 / artifact 13409568754 / commit `457d79d9...` | 1 / `0.1.0` | `d83462a95e7b24ec388fc58f56312c092637446758afa3ff702f62d62753a385` | `91:BE:5B:E1:68:BD:46:91:2B:08:6F:1C:C5:84:23:7B:AD:43:8A:56:AD:5B:6C:BA:A1:30:38:CF:91:88:9F:6C` | PR #9 pre-isolation CI APK; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42018167407) |
| `org.yggdrasil.keysteward.ci` | Run 42022960878 / artifact 13413185023 / commit `94c2259e...` | 1 / `0.1.0` | `a94cead8f2976767b2b5be65a95b99211f0dfc7f5c6bfecff1d55e24e77ab0ed` | `75:64:54:A0:7B:0D:80:8B:38:F0:79:C5:18:23:D3:FC:3B:F1:BC:38:B8:C3:B7:79:EC:7E:30:57:15:FC:EE:5A` | PR #9 disposable CI variant; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42022960878) |
| `org.yggdrasil.keysteward.dev` | Run 42022960878 / artifact 13413185023 / commit `94c2259e...` | 1 / `0.1.0-dev` | `9500ba21cebed4054e29dd98a0fc311fb014749ab30ef55b9b6e65210aa0760c` | `75:64:54:A0:7B:0D:80:8B:38:F0:79:C5:18:23:D3:FC:3B:F1:BC:38:B8:C3:B7:79:EC:7E:30:57:15:FC:EE:5A` | PR #9 disposable Development variant; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42022960878) |
| `org.yggdrasil.keysteward.ci` | Run 42057976537 / artifact 13430334198 / commit `55c119fb...` | 1 / `1.0.0-ci` | `305a90a229e72b9e6ddd336a031df704d5080e37820dd0d6bb86c810840aa12d` | `F5:2B:E5:20:7C:F8:C4:D7:4E:50:B6:48:95:D6:39:02:84:27:18:15:5E:B1:43:2A:C9:45:19:82:1F:F3:FF:88` | PR #15 draft integration; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42057976537) |
| `org.yggdrasil.keysteward.dev` | Run 42057976537 / artifact 13430334198 / commit `55c119fb...` | 1 / `1.0.0-dev` | `e56722b428789f2eb8bff6153891317b841df857ab048821ee3f4c9d42c09183` | `F5:2B:E5:20:7C:F8:C4:D7:4E:50:B6:48:95:D6:39:02:84:27:18:15:5E:B1:43:2A:C9:45:19:82:1F:F3:FF:88` | PR #15 draft integration; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42057976537) |
| `org.yggdrasil.signingdiagnostic` | Run 42060148545 / artifact 13431701343 / commit `5f6c7f49...` | 1 / `1.0.0` | `887ee4de78b8d61f7323737d42e74e22bb12eceb5d6c1b26fcef7d4f3395a41b` | `2A:7E:F8:FF:D5:13:6E:9D:7B:AD:68:03:66:73:5F:D1:3D:52:70:57:5C:35:91:ED:04:E6:FC:82:95:FB:B7:DB` | PR #16 draft diagnostic; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42060148545) |
| `org.yggdrasil.signerprobe` | Run 42077077404 / artifact 13439840585 / commit `a0ead06b...` | 1 / `1.0.0` | `1b04f7d146009464866c6e168f73f4680d142bf808f9291c2a85b5608df789b4` | `C3:B2:2F:44:F8:72:70:69:78:F9:A0:A8:62:B3:C6:E6:33:6B:98:6E:66:A4:F1:EC:8B:BD:19:D5:B2:21:6F:E6` | PR #31 draft probe; [run](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42077077404) |

### Continuity and workflow conclusions

- Builder Host's historical signer `91:C4:...`, run 420575 signer `A7:9B:...`, and latest run 420783 signer `82:F0:...` are three different certificates for the same production package ID. The two later APKs are debug-signed distributable artifacts; they are not verified update-compatible with one another or with the historical APK.
- Key Steward's primary-branch artifact is debug-signed under the production package ID. The three pre-isolation PR #9 artifacts also show different certificates across runs for that same package ID. This confirms the ephemeral-debug continuity risk in those builds.
- PR #9's current workflow changes the CI and Development package IDs to `.ci` and `.dev`, assembles the reserved Production package unsigned, and uses one app-generated disposable PKCS#12 identity for both test variants and repeat assemblies within a run. The same-run signer equality is verified; cross-run continuity and a permanent Production signer are not.
- PR #15 ports the package-isolation pattern to the current primary baseline but remains open/draft and unmerged. Its same-run test identity is also disposable.
- PR #16 adds `org.yggdrasil.signingdiagnostic`; PR #31 adds `org.yggdrasil.signerprobe`. Both diagnostic APKs are separate debug-signed packages on draft branches, not part of the current primary module set.
- No permanent signer baseline is established for either Builder Host or Key Steward. The historical Builder Host fingerprint is not a live-device baseline.

### Installed-device status and safe boundary

- The installed Builder Host signer fingerprint remains **unknown**. The historical `91:C4:...` value identifies the historical APK only.
- The installed Key Steward signer fingerprint remains **unknown**.
- No device-side report was available in this monitoring pass. The draft diagnostic APKs have not been confirmed installed or run on the Galaxy S21.
- Do not install any of the debug-signed Builder Host APKs as updates. Do not uninstall either target app, clear data, or use package-manager bypasses.
- The least-risk next evidence is a read-only signer report from the installed package on the phone, using a reviewed diagnostic app or a trusted local Android tooling path. Any signer migration remains blocked until app-data/journal export and restoration are verified, a recovery path exists, and the user explicitly approves the migration.
