#!/usr/bin/env python3
"""Demonstrate two-copy recovery, corruption detection, and evidence preservation.

This is a controlled local simulation. The two directories are separate paths,
not proof of independent devices, accounts, administrators, or failure domains.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys

from yim001 import AuditedMemoryStore, load_audited, save_audited
from yim001.checkpoints import create_checkpoint_record, verify_checkpoint_chain
from yim001.recovery import (
    RecoveryError,
    create_recovery_backup,
    load_recovery_backup,
    restore_recovery_backup_file,
    save_recovery_backup,
)


def run_demo(root: Path) -> dict[str, object]:
    root = root.resolve()
    primary = root / "copy-A"
    secondary = root / "copy-B"
    witness = root / "checkpoint-anchor"
    for directory in (primary, secondary, witness):
        directory.mkdir(parents=True, exist_ok=True)

    source_path = primary / "audited-memory.json"
    backup_a = primary / "recovery-backup.json"
    backup_b = secondary / "recovery-backup.json"
    checkpoint_path = witness / "latest-checkpoint.json"
    damaged_memory = secondary / "audited-memory-damaged.json"
    preserved_bytes = secondary / "pre-restore-original.bin"

    store = AuditedMemoryStore("recovery-demo-actor")
    store.create_record(
        {"demonstration": "portable recovery", "generation": 1},
        timestamp="2026-11-10T18:00:00Z",
    )
    save_audited(store, source_path)

    backup = create_recovery_backup(
        store, "demo-instance-A", created_at="2026-11-10T18:01:00Z"
    )
    save_recovery_backup(backup, backup_a)
    # Copy B models transfer to another storage location. It does not establish
    # independent custody; that depends on the actual storage infrastructure.
    shutil.copyfile(backup_a, backup_b)
    checkpoint = create_checkpoint_record(
        backup, 1, recorded_at="2026-11-10T18:02:00Z"
    )
    checkpoint_path.write_text(
        json.dumps(checkpoint, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )

    # Verify both intact copies against the checkpoint retained separately.
    anchor = json.loads(checkpoint_path.read_text(encoding="utf-8"))
    backup_a_loaded = load_recovery_backup(backup_a)
    backup_b_loaded = load_recovery_backup(backup_b)
    intact_report = verify_checkpoint_chain(
        [anchor], [backup_a_loaded, backup_b_loaded],
        expected_latest_sequence=1,
        expected_source_instance_id="demo-instance-A",
    )
    if intact_report["status"] != "VERIFIED":
        raise RuntimeError(f"intact copies did not verify: {intact_report}")

    # Deliberately damage one copy. Do not repair or overwrite that evidence.
    damaged = bytearray(backup_b.read_bytes())
    damaged[len(damaged) // 2] ^= 0x01
    backup_b.write_bytes(damaged)
    damaged_copy_detected = False
    try:
        load_recovery_backup(backup_b)
    except (RecoveryError, ValueError, json.JSONDecodeError):
        damaged_copy_detected = True
    if not damaged_copy_detected:
        raise RuntimeError("deliberately damaged copy was not rejected")

    # Restore into a destination containing known-corrupt bytes. The exact
    # original bytes must be preserved to a new, exclusive evidence file.
    damaged_memory.write_bytes(b'{"intentionally":"damaged","value":NaN}')
    before = damaged_memory.read_bytes()
    restore = restore_recovery_backup_file(
        backup_a, damaged_memory, authorized=True,
        preserve_path=preserved_bytes,
    )
    restored = load_audited(damaged_memory)
    if preserved_bytes.read_bytes() != before:
        raise RuntimeError("pre-restore evidence bytes changed")
    if restored.to_snapshot() != store.to_snapshot():
        raise RuntimeError("restored memory differs from original snapshot")

    return {
        "status": "DEMONSTRATION_PASSED",
        "root": str(root),
        "intact_copy_verification": intact_report,
        "damaged_copy_rejected": damaged_copy_detected,
        "restore_status": restore["status"],
        "pre_restore_bytes_preserved": preserved_bytes.exists(),
        "restored_snapshot_matches": True,
        "checkpoint_anchor_path": str(checkpoint_path),
        "copy_a_path": str(backup_a),
        "copy_b_path": str(backup_b),
        "important_limit": (
            "Separate directories on one machine do not prove independent "
            "custody or protection from shared failure."
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path,
        help="new or existing demonstration directory; must not contain demo outputs",
    )
    args = parser.parse_args(argv)
    if args.root is None:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="yim001-recovery-demo-") as temporary:
            result = run_demo(Path(temporary))
            print(json.dumps(result, indent=2, sort_keys=True))
    else:
        root = args.root
        if root.exists() and any(root.iterdir()):
            parser.error("--root must be empty; refusing to overwrite existing evidence")
        root.mkdir(parents=True, exist_ok=True)
        result = run_demo(root)
        print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
