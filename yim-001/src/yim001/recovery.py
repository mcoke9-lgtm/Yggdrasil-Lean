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


def restore_recovery_backup(
    backup: Any,
    destination_path: str | os.PathLike[str],
    *,
    authorized: bool = False,
    preserve_path: str | os.PathLike[str] | None = None,
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
