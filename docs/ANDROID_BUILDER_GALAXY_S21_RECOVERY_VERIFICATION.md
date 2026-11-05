# Galaxy S21 Builder Host Recovery Verification

**Status:** User-reported physical-device verification complete.

**Device:** Samsung Galaxy S21, Android 15.
**Build source:** `0a23267dc51deba4d5fab814b39bd3af51177e19`.
**Android CI:** Run `42005258590`, completed successfully; artifact `13403234570`.

## Observed sequence

1. Before uninstalling the original Builder Host, the user confirmed its Journal Integrity check reported a verified chain.
2. The user exported a fresh snapshot and confirmed it was present in Downloads.
3. Android rejected installing the new APK over the old installation with a package-conflict message. The original app remained accessible. The user then uninstalled the original Builder Host and installed the new APK. The separate Scheduler app was not to be modified.
4. The new Builder Host opened and displayed **Test recovery safeguards (safe)**.
5. The user reported that the recovery-safety test passed.
6. The user restored the saved snapshot into the new installation.
7. The user confirmed that Journal Integrity again reported a verified chain.

## Verification boundary

These are user-reported results from the physical phone; the device was not independently inspected by the development environment. The results support successful snapshot recovery and journal-chain verification after reinstall. The exact cause of Android's package conflict has not been established; signing-key mismatch is a possibility, not a verified diagnosis.

## Remaining work

- Compare APK signing certificates and configure persistent CI signing so future builds can update the installed app without an uninstall-and-restore cycle.
- Clarify the import validation/confirmation interface.
- Test offline behavior and other capability boundaries.

No protected branch was changed. This is a documentation-only checkpoint on `independent-builder-runtime-foundation-001`.


## Signing identity investigation — 2026-11-05

- Located and downloaded the exact CI artifact referenced by the Galaxy S21 recovery verification record: workflow run `42005258590`, artifact `13403234570`, built from commit `0a23267dc51deba4d5fab814b39bd3af51177e19`.
- Inspected the APK signing block and extracted its signer certificate. Certificate subject/issuer: `CN=Android Debug, O=Android, C=US`; serial `01`; SHA-256 fingerprint: `91:C4:25:17:E8:E8:73:B9:FD:C2:85:63:10:07:C9:8F:46:0B:0C:8B:95:5B:99:24:0F:55:2F:DB:DB:EC:2B:3A`.
- The recovery verification record associates this artifact with the APK installed during the user's prior one-time migration. This is the best available signer evidence for the installed build; direct inspection of the live phone is not available.
- This is the standard Android debug certificate, not a permanent Builder signing identity. The private key cannot be reconstructed from the APK. The old artifact can establish the certificate fingerprint but cannot be used to sign future builds.
- The current workflow's `apksigner verify --print-certs` step verifies APK signature validity and prints its signer digest; it does not by itself establish continuity with the installed APK. Persistent CI secrets remain absent, and no persistent-signed APK has been produced.
- **Migration status:** the user previously exported a fresh journal snapshot, uninstalled the original package after an Android package-conflict, installed the new Builder Host, passed the safe recovery test, restored the snapshot, and confirmed the journal chain. Those are user-reported results. A further change to a permanent signer would require another controlled migration unless the permanent certificate can be made to match the installed certificate; the matching private key is unavailable.
- Do not ask the user to uninstall or clear app data. Before any future signer migration, protect the current journal with a fresh export and verify the chain. The user has already experienced one migration; avoid repeating it unless essential.
- No signing secrets can be read or written through the currently connected GitHub integration. The user must create/store the permanent private key in a user-controlled secure location and enter the four GitHub Actions secrets through GitHub's protected settings. Never request secret values in chat.
