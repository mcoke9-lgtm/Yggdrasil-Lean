# YIM-001 portable recovery demonstration

**Status:** Reproducible local demonstration; not evidence of independent-device or remote custody.

The demonstration exercises an end-to-end recovery path:

1. Creates an audited memory snapshot.
2. Creates and validates a digest-protected recovery backup.
3. Writes copy A, then copies its bytes to a separate copy-B directory.
4. Retains the latest checkpoint record in a third directory.
5. Verifies both intact backup copies against the checkpoint record and explicit source/latest-sequence anchors.
6. Deliberately corrupts copy B and confirms strict backup loading rejects it.
7. Restores from intact copy A into a deliberately damaged memory destination.
8. Preserves the exact pre-restore destination bytes in a new exclusive evidence file.
9. Reloads the restored snapshot and confirms it matches the original audited snapshot.

## Run

From the repository root:

```sh
PYTHONPATH=yim-001/src python yim-001/scripts/recovery_demo.py
```

This uses a temporary directory and prints a JSON result. To retain the demonstration files, provide a new or empty directory:

```sh
PYTHONPATH=yim-001/src python yim-001/scripts/recovery_demo.py --root ./recovery-demo-output
```

The script refuses to run with `--root` when that directory already contains entries. It does not overwrite prior demonstration evidence.

## Expected result

The output reports `DEMONSTRATION_PASSED`, intact-copy verification status `VERIFIED`, detection of the deliberately damaged copy, successful restore, preserved pre-restore bytes, and a matching restored snapshot.

## What this demonstrates—and what it does not

It demonstrates portable file-level backup validation, checkpoint reference checking, corruption detection, guarded restore, and preservation of prior bytes.

The directories are separate paths, but they may reside on the same disk, machine, account, or administrative domain. The checkpoint anchor is also a local file in the demonstration. Therefore this is **not** proof of independent custody, off-device resilience, administrator separation, authenticated provenance, or protection against shared loss.

For a real custody test, place one backup and its checkpoint evidence under separately controlled storage and credentials, ideally on a physically or administratively independent medium. Transfer by an explicit, documented process; then verify after transfer. Retain an external latest-sequence anchor under separate control. Do not treat a second folder or second filename as independent custody.

No automatic scheduling, rotation, deletion, network transport, encryption, identity authentication, signatures, or concurrent-writer support is added.
