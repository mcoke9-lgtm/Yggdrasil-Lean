# Device Inventory Report Handoff

The Registry Dashboard remains offline and read-only. Its report handoff avoids making an attachment or network connection a requirement.

## Available actions

- **Copy JSON report** — copies a compact, structured report to the Android clipboard. Paste the text directly into ChatGPT; no file attachment is required.
- **Share JSON report** — opens Android's Sharesheet with the report as plain text.
- **Save JSON report** — opens Android's document picker and saves a JSON file only to a destination selected by the user.

## Report contents

Schema: `yggdrasil.device-inventory-report`, version `1.0.0`.

Each of the four explicitly registered package IDs has:
- display name, purpose, and package ID;
- an observation status;
- installed version name and version code when observable;
- current APK signer SHA-256 fingerprint(s);
- signing certificate history SHA-256 fingerprint(s);
- a clear distinction between not-found/not-visible and inspection errors.

The report also includes capture time, collector package ID, Android API level, and a plain-language statement of collection scope.

## Privacy and interpretation

- No network permission or network transmission is added.
- No broad installed-app inventory is collected.
- No serial number, Android ID, IMEI, account data, location, Wi-Fi identifiers, or personal files are collected.
- The report is generated locally when requested and leaves the device only through a user-selected copy/share/save action.
- `not_found_or_not_visible` is not proof that an app is absent.
- A report is a device observation at a particular time, not an automatic update to the canonical registry.
- Signer fingerprints are evidence for human review; they do not authorize an update, uninstall, replacement, or data clearing.

## Suggested workflow

1. Open Yggdrasil App Registry.
2. Tap **Refresh device inventory**.
3. Tap **Copy JSON report**.
4. Paste the report directly into the ChatGPT conversation.
5. Reconcile the observation against the canonical registry; preserve unknowns and discrepancies.

The app does not automatically upload, email, or transmit reports.
