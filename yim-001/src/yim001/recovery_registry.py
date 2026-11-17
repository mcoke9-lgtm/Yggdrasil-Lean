"""Append-only, hash-linked registry of known recovery evidence locations.

The registry records references to backup/checkpoint files; it never copies,
moves, restores, or modifies the referenced evidence.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .storage import StorageError, read_json_strict, write_json_atomic

FORMAT = "YIM-001-Recovery-Location-Registry"
FORMAT_VERSION = 1


class RecoveryRegistryError(ValueError):
    """Raised when a recovery location registry is invalid."""


def _canonical(value: Any) -> bytes:
    try:
        return json.dumps(value, ensure_ascii=False, allow_nan=False,
                          sort_keys=True, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise RecoveryRegistryError(f"invalid registry JSON: {exc}") from exc


def _entry_digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(payload)).hexdigest()


def _validate_registry(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != {"format", "format_version", "registry_id", "entries"}:
        raise RecoveryRegistryError("invalid registry structure")
    if value["format"] != FORMAT or type(value["format_version"]) is not int or value["format_version"] != FORMAT_VERSION:
        raise RecoveryRegistryError("unsupported registry format or version")
    if not isinstance(value["registry_id"], str) or not value["registry_id"].strip():
        raise RecoveryRegistryError("invalid registry identity")
    if not isinstance(value["entries"], list):
        raise RecoveryRegistryError("registry entries must be a list")
    previous = None
    known_ids = set()
    for sequence, entry in enumerate(value["entries"], start=1):
        if not isinstance(entry, dict) or set(entry) != {"payload", "previous_digest", "digest"}:
            raise RecoveryRegistryError(f"invalid registry entry {sequence}")
        payload = entry["payload"]
        required = {"entry_id", "sequence", "kind", "path", "source_instance_id",
                    "logical_location", "depends_on", "note", "registered_at"}
        if not isinstance(payload, dict) or set(payload) != required:
            raise RecoveryRegistryError(f"invalid registry entry payload {sequence}")
        if payload["entry_id"] != f"location-{sequence:06d}" or payload["sequence"] != sequence:
            raise RecoveryRegistryError(f"invalid registry sequence or identity at {sequence}")
        if payload["kind"] not in ("backup", "checkpoint"):
            raise RecoveryRegistryError(f"invalid evidence kind at {sequence}")
        if not isinstance(payload["path"], str) or not payload["path"]:
            raise RecoveryRegistryError(f"invalid evidence path at {sequence}")
        for field in ("source_instance_id", "logical_location"):
            if payload[field] is not None and not isinstance(payload[field], str):
                raise RecoveryRegistryError(f"invalid {field} at {sequence}")
        if not isinstance(payload["depends_on"], list) or any(
            not isinstance(item, str) or item not in known_ids for item in payload["depends_on"]
        ):
            raise RecoveryRegistryError(f"dependencies must refer to earlier registry entries at {sequence}")
        if not isinstance(payload["note"], str) or not isinstance(payload["registered_at"], str):
            raise RecoveryRegistryError(f"invalid descriptive fields at {sequence}")
        if entry["previous_digest"] != previous or entry["digest"] != _entry_digest(payload):
            raise RecoveryRegistryError(f"registry hash-chain mismatch at {sequence}")
        known_ids.add(payload["entry_id"])
        previous = entry["digest"]
    return value


def create_recovery_registry(path: str | Path, registry_id: str) -> dict[str, Any]:
    destination = Path(path)
    if destination.exists():
        raise FileExistsError(f"refusing to overwrite existing recovery registry: {destination}")
    if not isinstance(registry_id, str) or not registry_id.strip():
        raise RecoveryRegistryError("registry_id must be a non-empty identifier")
    value = {"format": FORMAT, "format_version": FORMAT_VERSION,
             "registry_id": registry_id, "entries": []}
    write_json_atomic(destination, value)
    return value


def load_recovery_registry(path: str | Path) -> dict[str, Any]:
    try:
        return _validate_registry(read_json_strict(path))
    except StorageError as exc:
        raise RecoveryRegistryError(str(exc)) from exc


def register_recovery_location(
    registry_path: str | Path,
    evidence_path: str | Path,
    kind: str,
    *,
    source_instance_id: str | None = None,
    logical_location: str | None = None,
    depends_on: list[str] | None = None,
    note: str = "",
    registered_at: str | None = None,
) -> dict[str, Any]:
    registry = load_recovery_registry(registry_path)
    if kind not in ("backup", "checkpoint"):
        raise RecoveryRegistryError("kind must be 'backup' or 'checkpoint'")
    for field, value in (("source_instance_id", source_instance_id),
                         ("logical_location", logical_location)):
        if value is not None and (not isinstance(value, str) or not value.strip()):
            raise RecoveryRegistryError(f"{field} must be a non-empty string when supplied")
    if not isinstance(note, str):
        raise RecoveryRegistryError("note must be a string")
    dependencies = [] if depends_on is None else depends_on
    known_ids = {item["payload"]["entry_id"] for item in registry["entries"]}
    if not isinstance(dependencies, list) or len(set(dependencies)) != len(dependencies) or any(
        item not in known_ids for item in dependencies
    ):
        raise RecoveryRegistryError("dependencies must be unique IDs of earlier registry entries")
    stamp = registered_at or datetime.now(timezone.utc).isoformat(timespec="microseconds")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as exc:
        raise RecoveryRegistryError("registered_at must be timezone-qualified ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise RecoveryRegistryError("registered_at must include a UTC offset")
    sequence = len(registry["entries"]) + 1
    payload = {
        "entry_id": f"location-{sequence:06d}",
        "sequence": sequence,
        "kind": kind,
        "path": str(Path(evidence_path).expanduser().absolute()),
        "source_instance_id": source_instance_id,
        "logical_location": logical_location,
        "depends_on": list(dependencies),
        "note": note,
        "registered_at": stamp,
    }
    previous = registry["entries"][-1]["digest"] if registry["entries"] else None
    entry = {"payload": payload, "previous_digest": previous,
             "digest": _entry_digest(payload)}
    registry["entries"].append(entry)
    _validate_registry(registry)
    write_json_atomic(registry_path, registry)
    return entry


def search_recovery_registry(record_id: str, registry_path: str | Path) -> dict[str, Any]:
    from .recovery_search import search_recovery_locations

    registry = load_recovery_registry(registry_path)
    entries = [item["payload"] for item in registry["entries"]]
    backups = [item["path"] for item in entries if item["kind"] == "backup"]
    checkpoints = [item["path"] for item in entries if item["kind"] == "checkpoint"]
    result = search_recovery_locations(record_id, backup_paths=backups,
                                       checkpoint_paths=checkpoints)
    result["registry"] = {
        "registry_id": registry["registry_id"],
        "entries": entries,
        "hash_chain_entries_validated": len(entries),
        "registry_digest": registry["entries"][-1]["digest"] if entries else None,
    }
    entries_by_path = {
        str(Path(item["path"]).expanduser().absolute()): item
        for item in entries
    }
    lineage = result.get("lineage", {})
    for evidence in lineage.get("member_evidence", []):
        registered = entries_by_path.get(
            str(Path(evidence["path"]).expanduser().absolute())
        )
        if registered is not None:
            evidence["registry_location"] = {
                "entry_id": registered["entry_id"],
                "kind": registered["kind"],
                "logical_location": registered["logical_location"],
                "depends_on": list(registered["depends_on"]),
                "registered_at": registered["registered_at"],
            }
        else:
            evidence["registry_location"] = None
    for relationship in lineage.get("relationships", []):
        for evidence in relationship.get("evidence", []):
            registered = entries_by_path.get(
                str(Path(evidence["path"]).expanduser().absolute())
            )
            if registered is not None:
                evidence["registry_location"] = {
                    "entry_id": registered["entry_id"],
                    "kind": registered["kind"],
                    "logical_location": registered["logical_location"],
                    "depends_on": list(registered["depends_on"]),
                }
            else:
                evidence["registry_location"] = None
    result["expected_logical_locations"] = sorted({
        item["logical_location"] for item in entries
        if item["logical_location"] is not None
    })
    searched_by_path = {
        str(Path(item["path"]).expanduser().absolute()): item
        for item in result.get("searched_locations", [])
    }
    entries_by_id = {item["entry_id"]: item for item in entries}
    dependency_report = []
    def dependency_closure(entry_id: str) -> list[str]:
        ordered: list[str] = []
        visited: set[str] = set()
        def visit(current_id: str) -> None:
            for parent_id in entries_by_id[current_id]["depends_on"]:
                if parent_id not in visited:
                    visited.add(parent_id)
                    ordered.append(parent_id)
                    visit(parent_id)
        visit(entry_id)
        return ordered

    for item in entries:
        dependency_details = []
        for dependency_id in item["depends_on"]:
            dependency = entries_by_id[dependency_id]
            dependency_path = str(Path(dependency["path"]).expanduser().absolute())
            search_result = searched_by_path.get(dependency_path)
            dependency_details.append({
                "entry_id": dependency_id,
                "kind": dependency["kind"],
                "path": dependency["path"],
                "logical_location": dependency["logical_location"],
                "search_status": (
                    search_result["status"] if search_result is not None
                    else "not_searched_by_this_registry_query"
                ),
                "dependency_is_metadata_not_custody_proof": True,
            })
        dependency_report.append({
            "entry_id": item["entry_id"],
            "dependencies": dependency_details,
            "transitive_dependency_entry_ids": dependency_closure(item["entry_id"]),
        })
    result["registered_dependencies"] = [
        {"entry_id": item["entry_id"], "depends_on": item["depends_on"]}
        for item in entries if item["depends_on"]
    ]
    result["dependency_report"] = dependency_report
    result["scope_limit"] = (
        "Search used only evidence paths recorded in this validated registry. "
        "The registry is a local reference manifest, not proof that referenced "
        "files remain available or that its latest contents are externally authenticated."
    )
    return result
