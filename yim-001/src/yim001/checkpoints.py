"""Append-only, hash-linked recovery checkpoint records.

These records verify sequence continuity and backup references. They do not
authenticate authorship, prove independent custody, or establish that a
caller-supplied latest-sequence anchor is trustworthy.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any, Iterable

from .core import _validate_json
from .recovery import inspect_recovery_backup

FORMAT = "YIM-001-Recovery-Checkpoint"
FORMAT_VERSION = 1


class CheckpointError(ValueError):
    """Raised when a recovery checkpoint record or chain is invalid."""


def _canonical(value: Any) -> bytes:
    _validate_json(value, "checkpoint")
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False,
                          sort_keys=True, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise CheckpointError(f"invalid checkpoint JSON: {exc}") from exc


def _digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(payload)).hexdigest()


def create_checkpoint_record(
    backup: Any,
    sequence: int,
    *,
    previous_checkpoint_sha256: str | None = None,
    recorded_at: str | None = None,
) -> dict[str, Any]:
    """Create one checkpoint record linked to the preceding record digest."""
    checked = inspect_recovery_backup(backup)
    if type(sequence) is not int or sequence < 1:
        raise CheckpointError("sequence must be a positive integer")
    if sequence == 1 and previous_checkpoint_sha256 is not None:
        raise CheckpointError("first checkpoint must not have a predecessor")
    if sequence > 1 and (
        not isinstance(previous_checkpoint_sha256, str)
        or len(previous_checkpoint_sha256) != 64
        or any(c not in "0123456789abcdef" for c in previous_checkpoint_sha256)
    ):
        raise CheckpointError("later checkpoints require a lowercase SHA-256 predecessor digest")
    stamp = recorded_at or datetime.now(timezone.utc).isoformat(timespec="microseconds")
    if not isinstance(stamp, str) or not stamp:
        raise CheckpointError("recorded_at must be a timezone-qualified ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CheckpointError("recorded_at must be valid ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise CheckpointError("recorded_at must include a UTC offset")
    payload = {
        "format": FORMAT,
        "format_version": FORMAT_VERSION,
        "sequence": sequence,
        "source_instance_id": checked["source_instance_id"],
        "recorded_at": stamp,
        "backup_sha256": checked["sha256"],
        "previous_checkpoint_sha256": previous_checkpoint_sha256,
    }
    return {"payload": payload, "sha256": _digest(payload)}


def inspect_checkpoint_record(record: Any) -> dict[str, Any]:
    if not isinstance(record, dict) or set(record) != {"payload", "sha256"}:
        raise CheckpointError("invalid checkpoint record structure")
    payload = record["payload"]
    required = {
        "format", "format_version", "sequence", "source_instance_id",
        "recorded_at", "backup_sha256", "previous_checkpoint_sha256",
    }
    if not isinstance(payload, dict) or set(payload) != required:
        raise CheckpointError("invalid checkpoint payload structure")
    if payload["format"] != FORMAT or type(payload["format_version"]) is not int or payload["format_version"] != FORMAT_VERSION:
        raise CheckpointError("unsupported checkpoint format or version")
    if type(payload["sequence"]) is not int or payload["sequence"] < 1:
        raise CheckpointError("invalid checkpoint sequence")
    source = payload["source_instance_id"]
    if not isinstance(source, str) or not source.strip():
        raise CheckpointError("invalid checkpoint source identity")
    stamp = payload["recorded_at"]
    if not isinstance(stamp, str):
        raise CheckpointError("invalid checkpoint timestamp")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CheckpointError("invalid checkpoint timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise CheckpointError("checkpoint timestamp must include a UTC offset")
    backup_digest = payload["backup_sha256"]
    if not isinstance(backup_digest, str) or len(backup_digest) != 64 or any(c not in "0123456789abcdef" for c in backup_digest):
        raise CheckpointError("invalid backup digest reference")
    previous = payload["previous_checkpoint_sha256"]
    if payload["sequence"] == 1:
        if previous is not None:
            raise CheckpointError("first checkpoint must not have a predecessor")
    elif not isinstance(previous, str) or len(previous) != 64 or any(c not in "0123456789abcdef" for c in previous):
        raise CheckpointError("later checkpoint requires a predecessor digest")
    expected = _digest(payload)
    if not isinstance(record["sha256"], str) or record["sha256"] != expected:
        raise CheckpointError("checkpoint digest mismatch")
    return {
        "sequence": payload["sequence"],
        "source_instance_id": source,
        "recorded_at": stamp,
        "backup_sha256": backup_digest,
        "previous_checkpoint_sha256": previous,
        "sha256": expected,
    }


def verify_checkpoint_chain(
    records: Iterable[Any],
    backups: Iterable[Any],
    *,
    expected_latest_sequence: int | None = None,
    expected_source_instance_id: str | None = None,
) -> dict[str, Any]:
    """Verify records and referenced backups; caller supplies any external anchor.

    Outcomes: VERIFIED, UNCERTAIN, STALE, INCOMPLETE, or CONFLICT.
    VERIFIED means only that the supplied material is internally consistent
    and matches the supplied anchor; it does not authenticate that anchor.
    """
    try:
        checked = [inspect_checkpoint_record(item) for item in records]
        backup_map: dict[str, dict[str, Any]] = {}
        for backup in backups:
            info = inspect_recovery_backup(backup)
            digest = info["sha256"]
            if digest in backup_map:
                continue
            backup_map[digest] = info
    except (CheckpointError, ValueError, TypeError) as exc:
        return {"status": "CONFLICT", "verified_records": 0, "missing_backup_digests": [], "issues": [str(exc)]}

    if not checked:
        return {"status": "INCOMPLETE", "verified_records": 0, "missing_backup_digests": [], "issues": ["no checkpoint records supplied"]}
    checked.sort(key=lambda item: item["sequence"])
    issues: list[str] = []
    missing: list[str] = []
    status = "VERIFIED"
    seen_sequences: dict[int, str] = {}
    prior = None
    expected_source = expected_source_instance_id
    for item in checked:
        seq = item["sequence"]
        if seq in seen_sequences and seen_sequences[seq] != item["sha256"]:
            status = "CONFLICT"
            issues.append(f"fork: multiple records claim sequence {seq}")
        seen_sequences[seq] = item["sha256"]
        if expected_source is None:
            expected_source = item["source_instance_id"]
        if item["source_instance_id"] != expected_source:
            status = "CONFLICT"
            issues.append(f"source identity changes at sequence {seq}")
        if prior is None:
            if seq != 1:
                if status != "CONFLICT":
                    status = "INCOMPLETE"
                issues.append(f"chain begins at sequence {seq}, not 1")
            if item["previous_checkpoint_sha256"] is not None:
                status = "CONFLICT"
                issues.append("first supplied checkpoint has a predecessor")
        else:
            if seq != prior["sequence"] + 1:
                if status != "CONFLICT":
                    status = "INCOMPLETE"
                issues.append(f"sequence gap before {seq}")
            if item["previous_checkpoint_sha256"] != prior["sha256"]:
                status = "CONFLICT"
                issues.append(f"predecessor digest mismatch at sequence {seq}")
        if item["backup_sha256"] not in backup_map:
            missing.append(item["backup_sha256"])
            if status not in ("CONFLICT",):
                status = "INCOMPLETE"
        elif backup_map[item["backup_sha256"]]["source_instance_id"] != item["source_instance_id"]:
            status = "CONFLICT"
            issues.append(f"backup source identity mismatch at sequence {seq}")
        prior = item

    latest = checked[-1]["sequence"]
    if expected_latest_sequence is not None:
        if type(expected_latest_sequence) is not int or expected_latest_sequence < 1:
            return {"status": "CONFLICT", "verified_records": len(checked), "missing_backup_digests": sorted(set(missing)), "issues": issues + ["invalid expected latest sequence"]}
        if latest < expected_latest_sequence and status not in ("CONFLICT",):
            status = "STALE"
            issues.append(f"latest supplied sequence {latest} is behind external anchor {expected_latest_sequence}")
        elif latest > expected_latest_sequence:
            status = "CONFLICT"
            issues.append(f"latest supplied sequence {latest} exceeds external anchor {expected_latest_sequence}")
    elif status == "VERIFIED":
        status = "UNCERTAIN"
        issues.append("no externally retained latest-sequence anchor supplied")

    return {
        "status": status,
        "verified_records": len(checked),
        "latest_sequence": latest,
        "source_instance_id": expected_source,
        "missing_backup_digests": sorted(set(missing)),
        "issues": issues,
        "anchor_authenticated": False,
    }
