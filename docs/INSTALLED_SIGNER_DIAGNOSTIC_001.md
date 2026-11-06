# Installed Signer Diagnostic 001

## Purpose
Provide a separate, read-only Android utility to inspect the currently installed signing certificates for exactly two package IDs:
- `org.yggdrasil.builderhost`
- `org.yggdrasil.keysteward`

## Safety boundary
- Separate package ID: `org.yggdrasil.signingdiagnostic`
- Android package visibility is limited to the two explicit `<queries>` entries.
- No INTERNET or network-state permission.
- No app install/update/uninstall, data clearing, file export, clipboard access, private-key access, or signing-key generation.
- Reads PackageManager metadata and public APK signing certificates only.
- Uses Android `SigningInfo` (API 28+) to report current signer certificate SHA-256 fingerprints and, for single-signer packages, any available signing-certificate history.
- The diagnostic APK is a new app; it is not an update to Builder Host or Key Steward.

## Use
Install only the separately built diagnostic APK after reviewing its package identity and signer. Open it and read the two reports. A missing package is reported as not found/not visible rather than assumed absent. Compare the fingerprints with known historical artifact certificates only as historical references; the installed device values are authoritative for the installed packages.

## Limitations
A certificate fingerprint is not the private key. Signing lineage/history may not be available for every APK. This tool does not prove that a locally held keystore matches an installed signer unless the certificate fingerprints are compared independently.

## Status
Source implementation proposed. Android build, APK inspection, and on-device execution are pending CI and user-authorized installation.
