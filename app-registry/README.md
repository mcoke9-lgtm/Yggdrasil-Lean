# Yggdrasil App Registry

The registry is the canonical inventory for Yggdrasil applications and components. It exists so that people and Builders can answer **what exists, what it does, where it came from, and what is actually installed** without reconstructing that information from chat history or browsing multiple GitHub branches.

## Files

- `registry.json` — machine-readable canonical inventory.
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
