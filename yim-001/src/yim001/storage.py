"""Strict JSON snapshot helpers with atomic replacement.

These helpers are a persistence primitive; they do not yet serialize MemoryStore.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from typing import Any

from .core import ValidationError, _snapshot, _validate_json


class StorageError(ValueError):
    """Raised when a snapshot cannot be parsed as strict JSON."""


def _reject_constant(value: str) -> None:
    raise StorageError(f"non-JSON numeric constant: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise StorageError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def read_json_strict(path: str | os.PathLike[str]) -> Any:
    """Read one JSON value, rejecting non-standard constants and duplicate keys."""
    try:
        with Path(path).open("r", encoding="utf-8") as stream:
            value = json.load(
                stream,
                parse_constant=_reject_constant,
                object_pairs_hook=_unique_object,
            )
        _validate_json(value)
        return _snapshot(value, "snapshot")
    except StorageError:
        raise
    except (UnicodeError, json.JSONDecodeError, ValidationError, OSError, ValueError) as exc:
        raise StorageError(f"invalid or unreadable JSON snapshot: {exc}") from exc


def write_json_atomic(path: str | os.PathLike[str], value: Any) -> None:
    """Write a strict JSON snapshot using a same-directory temporary file.

    The existing destination is not replaced until the complete temporary
    file has been written and fsynced. A failure before replacement leaves the
    previous destination intact.
    """
    _validate_json(value)
    payload = json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    )

    destination = Path(path)
    parent = destination.parent
    if not parent.is_dir():
        raise FileNotFoundError(f"snapshot directory does not exist: {parent}")

    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.",
        suffix=".tmp",
        dir=str(parent),
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, destination)
        # Best-effort directory sync makes the rename durable on platforms
        # that support opening and syncing directory descriptors.
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
            temporary_path.unlink(missing_ok=True)
        except OSError:
            pass
        raise
