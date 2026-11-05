# Yggdrasil App Registry

The registry is the canonical inventory for Yggdrasil applications and components. It exists so that people and Builders can answer **what exists, what it does, where it came from, and what is actually installed** without reconstructing that information from chat history or browsing multiple GitHub branches.

## Files

- `registry.json` — machine-readable canonical inventory.
- `artifact-history.json` — canonical per-artifact build, publication, download-evidence, installation-attempt, and incident history.
- `operational-limits.json` — shared operating constraints, capacity observations, recheck triggers, and output-adaptation rules for the Nine Builders.
- This document — human-readable rules and operating guidance.

## Status meanings

| Status | Meaning |
|---|---|
| `planned` | Intended; no implementation evidence recorded. |
| `built` | Source or a build artifact exists. Installation is not implied. |
| `user_reported_installed` | The user reports installation; no device-side confirmation yet. |
| `device_verified_installed` | A device-side observation confirms package and version at a recorded time. |
| `unverified` | Available evidence is insufficient for a stronger claim. |

## Evidence rules

1. A successful build is not proof of installation.
2. An artifact checksum identifies the artifact; it does not identify the installed app's signer.
3. Record package ID, version, source commit, artifact SHA-256, and signing-certificate SHA-256 as separate facts.
4. Preserve evidence source, observation time, and verification method.
5. Unknown values remain unknown. Never infer a missing package ID, fingerprint, installation, or permission set.
6. Device inventory should be permission-light and privacy-minimized. Do not collect serial numbers, IMEI, Android IDs, account data, location, Wi-Fi identifiers, personal files, or a full installed-app inventory by default.

## Initial operating model

- **Canonical record:** `registry.json` in this repository.
- **First device:** Galaxy S21+ 5G, model SM-G996U, Android 15.
- **Future interface:** a small Android-friendly registry view, with updates supplied by a narrowly scoped Device Bridge when available.
- **Recovery:** the registry remains readable as plain JSON and Markdown if the dashboard or one platform is unavailable.

The initial records deliberately preserve uncertainty. Builder Host and Key Steward are marked as user-reported installed, not device-verified. Yggdrasil Scheduler has build evidence but its installation is unverified. The signer diagnostic is a separate diagnostic utility, not a production dependency.

## Update protocol

Every change should identify the record changed, prior value, new value, evidence, timestamp, and responsible source. Automated device reports may propose updates, but must not silently overwrite conflicting evidence. Retain prior observations as audit history when the registry evolves.


## Installation gate and device bridge

- `device_inventory_scope` is the exact allowlist of package IDs the Android dashboard may inspect.
- The dashboard uses Android package visibility declarations for those IDs only. It does not request permissions, use the network, or enumerate all installed apps.
- It displays installed version and current/history signer SHA-256 fingerprints where Android exposes them. Fingerprint text is selectable for copying.
- `NOT FOUND / NOT VISIBLE` is intentionally not treated as proof that a package is absent.
- The dashboard is read-only. It cannot install, update, uninstall, clear data, or silently change registry status.
- Before any new installation, compare package ID, version code, artifact SHA-256, and signing certificate. A package-ID collision or signer mismatch blocks replacement pending human review.
- A device observation is evidence for review; it does not automatically overwrite conflicting registry history.

## Current unresolved installation incident

On 2026-11-05 the user reported that Android displayed an existing-package conflict while attempting to install the Installed Signer Diagnostic APK. The identity and signer of the existing package are unknown. No app should be uninstalled or replaced until the dashboard inventory and signer evidence are reviewed.

## Nine Builders pre-action limits gate

- Before proposing or attempting a capability-dependent action, invoke `tools/check_operational_limits.py` with the exact capability name recorded in `operational-limits.json`.
- The gate is read-only. It does not query live account/provider telemetry, alter the register, execute the action, or free capacity.
- Decisions: `proceed`, `proceed_with_constraints`, `blocked`, `recheck_required`, or `authorization_required`.
- Unknown capabilities, malformed observations, and observations older than the configured freshness window fail closed to `recheck_required`; they are not treated as available.
- A fresh blocked observation stops the action. A constrained observation may proceed only while honoring its recorded conditions. Explicitly excluded actions require separate authorization.
- Default evidence freshness is seven days; use a stricter threshold for rapidly changing limits. Recheck dynamic account or provider capacity through the authoritative connected service before relying on it.
- Exit codes: 0 proceed/conditioned; 2 blocked; 3 recheck required; 4 authorization required.
- CI runs unit tests and confirms that the current scheduled-task capacity observation is blocked. This is a regression test of the recorded evidence, not live account telemetry.
- The Nine Builders must treat the gate as a required pre-action check in their orchestration. CI passing alone does not prove that every runtime or conversation path invokes it.
