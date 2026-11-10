# YIM-001 receiver-side recovery verification

**Status:** Read-only receiver verification implemented in the draft branch. It has not yet been exercised on a physical USB drive or independently controlled destination.

The receiver command checks transferred evidence without importing or restoring it. It requires:

- the complete checkpoint chain as a JSON list;
- every recovery backup referenced by that chain (one `--backup` argument per file);
- the expected source-instance ID;
- the expected latest sequence, obtained independently of the transferred files.

Run from the repository root:

```sh
PYTHONPATH=yim-001/src python yim-001/scripts/recovery_receive.py \
  --checkpoints /path/to/usb/checkpoints.json \
  --backup /path/to/usb/backup-0001.json \
  --expected-source phone-instance \
  --expected-latest-sequence 1
```

For a multi-checkpoint chain, repeat `--backup` for every referenced backup. Supply the complete checkpoint list in sequence order (the verifier validates and sorts the records).

Exit status is zero only for `VERIFIED`. Other statuses return 2 and remain visible in the JSON report. The command reports `receiver_check: read_only` and `files_modified: false`; it does not write to the supplied files, import records, or restore a destination.

## Transfer procedure for USB

1. On the source system, create a recovery backup and its checkpoint record using the existing APIs.
2. Copy the backup, complete checkpoint-chain JSON, and separately retained source/latest-sequence anchor to the USB according to the custody plan.
3. Safely eject and reconnect the USB (or connect it to the receiving system).
4. Run the receiver command against the USB paths, supplying the source ID and latest sequence from an independently retained anchor—not merely by copying those values from the same USB.
5. Record the receiver's JSON output and the physical device/custody details outside the backup bundle.
6. Do not restore or overwrite any destination as part of verification.

A `VERIFIED` result means the supplied files are internally consistent with the supplied anchors. It does not authenticate those anchors, identify the USB device, prove that it is physically independent, or establish who controlled it. Keep the anchor separately controlled. Do not store secrets or credentials in the bundle.

This is a verification-only step. Actual USB transfer, post-reconnect verification, physical custody, and any later restore remain operational steps requiring access to the device and explicit restore authorization.
