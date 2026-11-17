"""Bounded recovery search over caller-supplied backup and checkpoint locations.

This module never scans the filesystem implicitly. A no-result outcome describes
only the explicit locations supplied by the caller.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .checkpoints import CheckpointError, inspect_checkpoint_record
from .recovery import RecoveryError, inspect_recovery_backup, load_recovery_backup
from .storage import read_json_strict


def search_recovery_locations(
    record_id: str,
    *,
    backup_paths: list[str | Path],
    checkpoint_paths: list[str | Path] | None = None,
    expected_source_instance_id: str | None = None,
    expected_logical_location: str | None = None,
) -> dict[str, Any]:
    """Inspect only the named files and report what was and was not established."""
    if not isinstance(record_id, str) or not record_id.strip():
        raise ValueError("record_id must be a non-empty identifier")
    if checkpoint_paths is None:
        checkpoint_paths = []
    if not isinstance(backup_paths, list) or not isinstance(checkpoint_paths, list):
        raise ValueError("backup_paths and checkpoint_paths must be lists")

    searched: list[dict[str, Any]] = []
    found: list[dict[str, Any]] = []
    valid_backups: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    inaccessible: list[dict[str, Any]] = []

    for supplied in backup_paths:
        path = Path(supplied)
        entry = {"path": str(path), "kind": "backup"}
        if not path.exists():
            entry.update(status="not_present_at_supplied_path")
            searched.append(entry)
            continue
        if not path.is_file():
            entry.update(status="not_a_regular_file")
            inaccessible.append(entry)
            searched.append(entry)
            continue
        try:
            backup = load_recovery_backup(path)
            info = inspect_recovery_backup(backup)
        except (OSError, RecoveryError, ValueError, TypeError) as exc:
            entry.update(status="unreadable_or_invalid", reason=str(exc))
            invalid.append(entry)
            searched.append(entry)
            continue
        entry.update(status="read_and_validated", source_instance_id=info["source_instance_id"], backup_sha256=info["sha256"])
        searched.append(entry)
        valid_backups.append({"path": str(path), "backup": backup, "info": info})
        memory = info["audited_snapshot"]["memory"]
        matching_records = [item for item in memory["records"] if item["record_id"] == record_id]
        if matching_records:
            versions = [item for item in memory["versions"] if item["record_id"] == record_id]
            placements = [
                item for item in info["audited_snapshot"].get("living_tree", {}).get("placements", [])
                if item.get("record_id") == record_id
            ]
            found.append({
                "path": str(path),
                "source_instance_id": info["source_instance_id"],
                "backup_sha256": info["sha256"],
                "version_ids": [item["version_id"] for item in versions],
                "tree_placements": placements,
            })

    checkpoint_results: list[dict[str, Any]] = []
    for supplied in checkpoint_paths:
        path = Path(supplied)
        entry = {"path": str(path), "kind": "checkpoint"}
        if not path.exists():
            entry.update(status="not_present_at_supplied_path")
        elif not path.is_file():
            entry.update(status="not_a_regular_file")
            inaccessible.append(entry)
        else:
            try:
                checkpoint = read_json_strict(path)
                info = inspect_checkpoint_record(checkpoint)
                entry.update(status="read_and_validated", **info)
                checkpoint_results.append(info)
            except (OSError, CheckpointError, ValueError, TypeError) as exc:
                entry.update(status="unreadable_or_invalid", reason=str(exc))
                invalid.append(entry)
        searched.append(entry)

    matched_checkpoint_refs = [
        item for item in checkpoint_results
        if any(backup["info"]["sha256"] == item["backup_sha256"] for backup in valid_backups)
    ]
    unmatched_checkpoint_backup_digests = sorted({
        item["backup_sha256"] for item in checkpoint_results
        if not any(backup["info"]["sha256"] == item["backup_sha256"] for backup in valid_backups)
    })
    if expected_source_instance_id is not None:
        found = [item for item in found if item["source_instance_id"] == expected_source_instance_id]
    if expected_logical_location is not None:
        found = [
            item for item in found
            if any(
                placement.get("node_id") == expected_logical_location
                for placement in item["tree_placements"]
            )
        ]

    if found:
        status = "original_snapshot_found"
        interpretation = "A validated backup at a supplied location contains this record identity. This establishes availability in that backup, not current live-store presence or independent custody."
    elif valid_backups or checkpoint_results:
        status = "not_found_in_searched_locations"
        interpretation = "The record identity was not found in the inspected valid backups under the supplied filters. This does not establish deletion, nonexistence, or loss elsewhere."
    elif inaccessible:
        status = "locations_not_fully_accessible"
        interpretation = "At least one supplied location could not be inspected. No conclusion about the record's presence elsewhere is supported."
    elif invalid:
        status = "evidence_unreadable_or_invalid"
        interpretation = "Supplied evidence files existed but could not be validated. Their contents cannot support a recovery claim."
    else:
        status = "no_supplied_locations_available"
        interpretation = "No supplied backup or checkpoint file was available to inspect. No conclusion about deletion or nonexistence is supported."

    return {
        "status": status,
        "record_id": record_id,
        "expected_logical_location": expected_logical_location,
        "searched_locations": searched,
        "validated_backup_matches": found,
        "validated_checkpoint_count": len(checkpoint_results),
        "checkpoint_backup_references_matched": len(matched_checkpoint_refs),
        "checkpoint_backup_references_without_supplied_backup": unmatched_checkpoint_backup_digests,
        "inaccessible_locations": inaccessible,
        "invalid_evidence": invalid,
        "interpretation": interpretation,
        "scope_limit": "Only the exact backup_paths and checkpoint_paths supplied to this call were inspected. No implicit directory, device, account, cloud, or network search was performed.",
    }
