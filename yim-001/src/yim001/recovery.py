"""Validated, non-destructive recovery backups for audited YIM-001 snapshots.

Backup creation is separate from restore. Restore requires explicit
authorization and preserves the exact pre-restore destination bytes before
replacing an existing destination. This module assumes serialized single-writer
access and does not provide encryption, authentication, or concurrency locks.
"""
from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import tempfile
from typing import Any

from .audited import AuditedMemoryStore, load_audited, save_audited
from .core import _validate_json
from .storage import StorageError, read_json_strict

FORMAT = "YIM-001-Recovery-Backup"
FORMAT_VERSION = 1


class RecoveryError(ValueError):
    """Raised when a recovery backup or restore operation is invalid."""


def _canonical(value: Any) -> bytes:
    _validate_json(value, "recovery")
    try:
        return json.dumps(
            value, ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise RecoveryError(f"invalid recovery JSON: {exc}") from exc


def _digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(payload)).hexdigest()


def create_recovery_backup(
    source: AuditedMemoryStore,
    source_instance_id: str,
    *,
    created_at: str | None = None,
) -> dict[str, Any]:
    """Create a detached digest-protected backup envelope in memory."""
    if not isinstance(source, AuditedMemoryStore):
        raise TypeError("source must be an AuditedMemoryStore")
    if not isinstance(source_instance_id, str) or not source_instance_id.strip():
        raise RecoveryError("source_instance_id must be a non-empty caller-supplied identifier")
    stamp = created_at or datetime.now(timezone.utc).isoformat(timespec="microseconds")
    if not isinstance(stamp, str) or not stamp:
        raise RecoveryError("created_at must be a timezone-qualified ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RecoveryError("created_at must be valid ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise RecoveryError("created_at must include a UTC offset")
    payload = {
        "format": FORMAT,
        "format_version": FORMAT_VERSION,
        "source_instance_id": source_instance_id,
        "created_at": stamp,
        "audited_snapshot": source.to_snapshot(),
    }
    return {"payload": payload, "sha256": _digest(payload)}


def inspect_recovery_backup(backup: Any) -> dict[str, Any]:
    """Validate envelope digest and the complete nested audited snapshot."""
    if not isinstance(backup, dict) or set(backup) != {"payload", "sha256"}:
        raise RecoveryError("invalid recovery backup structure")
    payload = backup["payload"]
    required = {"format", "format_version", "source_instance_id", "created_at", "audited_snapshot"}
    if not isinstance(payload, dict) or set(payload) != required:
        raise RecoveryError("invalid recovery payload structure")
    if payload["format"] != FORMAT or type(payload["format_version"]) is not int or payload["format_version"] != FORMAT_VERSION:
        raise RecoveryError("unsupported recovery backup format or version")
    source_id, stamp = payload["source_instance_id"], payload["created_at"]
    if not isinstance(source_id, str) or not source_id.strip():
        raise RecoveryError("invalid source instance identity")
    if not isinstance(stamp, str) or not stamp:
        raise RecoveryError("invalid recovery timestamp")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RecoveryError("invalid recovery timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise RecoveryError("recovery timestamp must include a UTC offset")
    expected = _digest(payload)
    if not isinstance(backup["sha256"], str) or backup["sha256"] != expected:
        raise RecoveryError("recovery backup digest mismatch")
    audited = AuditedMemoryStore.from_snapshot(payload["audited_snapshot"])
    return {
        "source_instance_id": source_id,
        "created_at": stamp,
        "audited_snapshot": audited.to_snapshot(),
        "sha256": expected,
    }


def _write_new_bytes(path: str | os.PathLike[str], payload: bytes) -> None:
    """Atomically create a new file without replacing an existing path."""
    destination = Path(path)
    parent = destination.parent
    if not parent.is_dir():
        raise FileNotFoundError(f"recovery directory does not exist: {parent}")
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(f"refusing to overwrite existing recovery file: {destination}")
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".tmp", dir=str(parent)
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        # A hard link provides atomic create-if-absent semantics on the same
        # filesystem; it never replaces a pre-existing destination.
        os.link(temporary, destination)
        temporary.unlink()
        if hasattr(os, "O_DIRECTORY"):
            try:
                directory_fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
            except OSError:
                pass
    except BaseException:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def save_recovery_backup(backup: Any, path: str | os.PathLike[str]) -> None:
    """Validate and atomically create a new backup file; never overwrite."""
    inspect_recovery_backup(backup)
    payload = json.dumps(
        backup, ensure_ascii=True, allow_nan=False,
        separators=(",", ":"), sort_keys=True,
    ).encode("utf-8")
    _write_new_bytes(path, payload)


def load_recovery_backup(path: str | os.PathLike[str]) -> dict[str, Any]:
    """Read a strict JSON backup and validate all nested evidence."""
    try:
        envelope = read_json_strict(path)
        inspect_recovery_backup(envelope)
        return envelope
    except StorageError as exc:
        raise RecoveryError(f"unreadable recovery backup: {exc}") from exc


def create_recovery_restore_plan(
    backup_path: str | os.PathLike[str],
    record_id: str,
    destination_path: str | os.PathLike[str],
    *,
    preserve_path: str | os.PathLike[str] | None = None,
) -> dict[str, Any]:
    """Create a non-mutating, evidence-backed plan for a whole-store restore.

    The requested record is checked as a reason to select the backup, but the
    restore operation replaces the destination with the complete snapshot.
    This function never writes files and never authorizes a restore.
    """
    if not isinstance(record_id, str) or not record_id.strip():
        raise RecoveryError("record_id must be a non-empty identifier")
    source = Path(backup_path).expanduser().absolute()
    destination = Path(destination_path).expanduser().absolute()
    if not source.is_file():
        raise RecoveryError("backup source is not an available regular file")
    backup = load_recovery_backup(source)
    checked = inspect_recovery_backup(backup)
    snapshot = checked["audited_snapshot"]
    records = [
        item for item in snapshot["memory"]["records"]
        if item["record_id"] == record_id
    ]
    if not records:
        raise RecoveryError("validated backup does not contain the requested record")
    versions = [
        item for item in snapshot["memory"]["versions"]
        if item["record_id"] == record_id
    ]
    if destination.is_symlink():
        raise RecoveryError("refusing to plan restoration through a symbolic-link destination")
    if not destination.parent.is_dir():
        raise RecoveryError("destination parent directory is unavailable")
    destination_exists = destination.exists()
    destination_digest = None
    if destination_exists:
        if not destination.is_file():
            raise RecoveryError("existing destination is not a regular file")
        if preserve_path is None:
            raise RecoveryError("preserve_path is required when destination already exists")
        preservation = Path(preserve_path).expanduser().absolute()
        if preservation == destination:
            raise RecoveryError("preservation path must differ from destination path")
        if preservation.exists() or preservation.is_symlink():
            raise RecoveryError("preservation path already exists; refusing to overwrite it")
        destination_digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    elif preserve_path is not None:
        raise RecoveryError("preserve_path is only valid when destination already exists")
    return {
        "status": "plan_ready_pending_authorization",
        "source": {
            "path": str(source),
            "source_instance_id": checked["source_instance_id"],
            "backup_sha256": checked["sha256"],
            "validated": True,
        },
        "requested_record": {
            "record_id": record_id,
            "version_ids": [item["version_id"] for item in versions],
            "present_in_validated_backup": True,
        },
        "destination": {
            "path": str(destination),
            "existed_when_planned": destination_exists,
            "sha256_when_planned": destination_digest,
            "preserve_path": (
                str(Path(preserve_path).expanduser().absolute())
                if preserve_path is not None else None
            ),
        },
        "restore_scope": "entire_audited_store_snapshot",
        "restore_will_replace_destination_store": True,
        "authorization_required": True,
        "authorized": False,
        "plan_is_not_a_restore": True,
        "staleness_warning": (
            "Revalidate source digest and destination state immediately before restore; "
            "this plan does not lock either file or prevent later changes."
        ),
    }



def revalidate_recovery_restore_plan(plan: Any) -> dict[str, Any]:
    """Recheck the exact source, record membership, and destination state."""
    if not isinstance(plan, dict) or plan.get("plan_is_not_a_restore") is not True:
        raise RecoveryError("invalid recovery restore plan")
    if plan.get("authorized") is not False or plan.get("authorization_required") is not True:
        raise RecoveryError("restore plan authorization state is invalid")
    try:
        source_path = Path(plan["source"]["path"])
        expected_source_id = plan["source"]["source_instance_id"]
        expected_backup_digest = plan["source"]["backup_sha256"]
        record_id = plan["requested_record"]["record_id"]
        expected_versions = plan["requested_record"]["version_ids"]
        destination = Path(plan["destination"]["path"])
        expected_exists = plan["destination"]["existed_when_planned"]
        expected_destination_digest = plan["destination"]["sha256_when_planned"]
        preserve_value = plan["destination"]["preserve_path"]
    except (KeyError, TypeError) as exc:
        raise RecoveryError("recovery restore plan is incomplete") from exc
    if not source_path.is_file():
        raise RecoveryError("restore plan is stale: source backup is unavailable")
    try:
        backup = load_recovery_backup(source_path)
        checked = inspect_recovery_backup(backup)
    except (OSError, RecoveryError, ValueError, TypeError) as exc:
        raise RecoveryError(f"restore plan is stale: source backup is no longer valid ({exc})") from exc
    if checked["source_instance_id"] != expected_source_id or checked["sha256"] != expected_backup_digest:
        raise RecoveryError("restore plan is stale: source backup identity or digest changed")
    snapshot = checked["audited_snapshot"]
    if not any(row["record_id"] == record_id for row in snapshot["memory"]["records"]):
        raise RecoveryError("restore plan is stale: requested record is no longer present")
    current_versions = [
        row["version_id"] for row in snapshot["memory"]["versions"]
        if row["record_id"] == record_id
    ]
    if current_versions != expected_versions:
        raise RecoveryError("restore plan is stale: requested record versions changed")
    if destination.is_symlink():
        raise RecoveryError("restore plan is stale: destination is now a symbolic link")
    exists_now = destination.exists()
    if exists_now != expected_exists:
        raise RecoveryError("restore plan is stale: destination existence changed")
    current_destination_digest = None
    if exists_now:
        if not destination.is_file():
            raise RecoveryError("restore plan is stale: destination is no longer a regular file")
        current_destination_digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    if current_destination_digest != expected_destination_digest:
        raise RecoveryError("restore plan is stale: destination contents changed")
    if exists_now:
        if not isinstance(preserve_value, str):
            raise RecoveryError("restore plan is missing its required preservation path")
        preservation = Path(preserve_value)
        if preservation == destination or preservation.exists() or preservation.is_symlink():
            raise RecoveryError("restore plan is stale: preservation path is unavailable")
    elif preserve_value is not None:
        raise RecoveryError("restore plan is invalid: unexpected preservation path")
    return {
        "status": "valid_pending_authorization",
        "source_backup_sha256": checked["sha256"],
        "requested_record_id": record_id,
        "destination_exists": exists_now,
        "destination_sha256": current_destination_digest,
        "authorization_required": True,
        "authorized": False,
        "no_files_modified": True,
    }


def restore_recovery_plan(plan: Any, *, authorized: bool = False) -> dict[str, Any]:
    """Revalidate a plan immediately before an explicitly authorized restore."""
    if authorized is not True:
        raise RecoveryError("explicit authorization is required to execute a recovery plan")
    revalidated = revalidate_recovery_restore_plan(plan)
    source_path = Path(plan["source"]["path"])
    backup = load_recovery_backup(source_path)
    checked = inspect_recovery_backup(backup)
    if checked["sha256"] != revalidated["source_backup_sha256"]:
        raise RecoveryError("restore plan is stale: source backup changed after revalidation")
    destination = Path(plan["destination"]["path"])
    return restore_recovery_backup(
        backup,
        destination,
        authorized=True,
        preserve_path=plan["destination"]["preserve_path"],
        expected_destination_exists=plan["destination"]["existed_when_planned"],
        expected_destination_sha256=plan["destination"]["sha256_when_planned"],
    )


def restore_recovery_backup(
    backup: Any,
    destination_path: str | os.PathLike[str],
    *,
    authorized: bool = False,
    preserve_path: str | os.PathLike[str] | None = None,
    expected_destination_exists: bool | None = None,
    expected_destination_sha256: str | None = None,
) -> dict[str, Any]:
    """Restore a validated backup, preserving existing destination bytes first.

    Authorization is checked before any file access. If the destination exists,
    preserve_path is mandatory and is created exclusively before replacement.
    A missing destination may be created without a preservation copy.
    """
    if authorized is not True:
        raise RecoveryError("explicit authorization is required to restore a backup")
    incoming = inspect_recovery_backup(backup)
    destination = Path(destination_path)
    if destination.is_symlink():
        raise RecoveryError("refusing to restore through a symbolic-link destination")
    existed = destination.exists()
    if expected_destination_exists is not None and existed is not expected_destination_exists:
        raise RecoveryError("destination state changed since recovery plan validation")
    if existed and expected_destination_sha256 is not None:
        if not destination.is_file():
            raise RecoveryError("existing destination is not a regular file")
        current_digest = hashlib.sha256(destination.read_bytes()).hexdigest()
        if current_digest != expected_destination_sha256:
            raise RecoveryError("destination contents changed since recovery plan validation")
    if not existed and expected_destination_sha256 is not None:
        raise RecoveryError("unexpected destination digest for missing destination")
    preserved = False
    if existed:
        if not destination.is_file():
            raise RecoveryError("existing destination is not a regular file")
        if preserve_path is None:
            raise RecoveryError("preserve_path is required when restoring over an existing destination")
        preservation = Path(preserve_path)
        if preservation.absolute() == destination.absolute():
            raise RecoveryError("preservation path must differ from destination path")
        raw_before = destination.read_bytes()
        _write_new_bytes(preservation, raw_before)
        preserved = True
    elif preserve_path is not None:
        # Do not create an empty or misleading preservation artifact.
        raise RecoveryError("preserve_path is only valid when the destination already exists")

    restored = AuditedMemoryStore.from_snapshot(incoming["audited_snapshot"])
    restored.record_recovery(incoming["source_instance_id"], incoming["sha256"])
    save_audited(restored, destination)
    return {
        "status": "restored",
        "source_instance_id": incoming["source_instance_id"],
        "backup_sha256": incoming["sha256"],
        "destination_path": str(destination),
        "previous_destination_existed": existed,
        "previous_destination_preserved": preserved,
        "preserve_path": str(preserve_path) if preserved else None,
    }


def restore_recovery_backup_file(
    backup_path: str | os.PathLike[str],
    destination_path: str | os.PathLike[str],
    *,
    authorized: bool = False,
    preserve_path: str | os.PathLike[str] | None = None,
) -> dict[str, Any]:
    """Load, validate, and explicitly restore a backup file."""
    if authorized is not True:
        raise RecoveryError("explicit authorization is required to restore a backup")
    return restore_recovery_backup(
        load_recovery_backup(backup_path),
        destination_path,
        authorized=True,
        preserve_path=preserve_path,
    )
