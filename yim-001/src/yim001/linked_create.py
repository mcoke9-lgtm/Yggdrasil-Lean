"""Opt-in linked Core Memory create protocol (YIM-001 Section 8.8)."""
from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any, Callable
from uuid import uuid4

from .core import MemoryStore
from .provenance import (
    EventLedger, LINKED_COMPLETION_SCHEMA, LINKED_INTENT_SCHEMA,
    ProvenanceError, _validate_operation_id, initial_version_digest,
)
from .storage import load_store, read_json_strict, save_store, write_json_atomic


class LinkedCreateError(ValueError):
    """A linked-create request was rejected or could not be resolved."""


@dataclass(frozen=True)
class LinkedCreateResult:
    outcome: str
    operation_id: str
    findings: tuple[str, ...]
    record_id: str | None = None
    version_id: str | None = None
    intent_event_id: str | None = None
    completion_event_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "outcome": self.outcome,
            "operation_id": self.operation_id,
            "findings": list(self.findings),
            "record_id": self.record_id,
            "version_id": self.version_id,
            "intent_event_id": self.intent_event_id,
            "completion_event_id": self.completion_event_id,
        }


def _new_candidate_id(occupied: set[str]) -> str:
    while True:
        value = str(uuid4())
        if value not in occupied:
            return value


def _candidate_store(store: MemoryStore, record_id: str, version_id: str,
                     content: Any, metadata: Any) -> MemoryStore:
    snapshot = store.to_snapshot()
    snapshot["records"].append({"record_id": record_id, "version_ids": [version_id]})
    snapshot["versions"].append({
        "version_id": version_id, "record_id": record_id, "parents": [],
        "content": content, "metadata": metadata,
    })
    return MemoryStore.from_snapshot(snapshot)


def _find_record_digest(store: MemoryStore, record_id: str, version_id: str) -> str | None:
    try:
        version = store.get_version(version_id)
    except (KeyError, ValueError):
        return None
    if version.record_id != record_id or version.parents:
        return None
    return initial_version_digest(record_id, version_id, version.content, version.metadata)


def linked_create(
    store_path: str,
    ledger_path: str,
    content: Any,
    metadata: Any,
    actor_id: str,
    operation_id: str,
    *,
    _fault: Callable[[str], None] | None = None,
) -> LinkedCreateResult:
    """Create or resume one linked operation; recovery is deliberately excluded."""
    _validate_operation_id(operation_id)
    if not isinstance(actor_id, str) or not actor_id.strip():
        raise ProvenanceError("actor_id must be a non-empty caller-supplied claim")
    if _fault is None:
        _fault = lambda _point: None

    # Complete-history preflight precedes all mutation.
    store = load_store(store_path)
    ledger = EventLedger.from_snapshot(read_json_strict(ledger_path))
    intents = [
        event for event in ledger.events
        if event.event_type == "record.create_intent"
        and isinstance(event.details, dict)
        and event.details.get("operation_id") == operation_id
    ]
    completions = [
        event for event in ledger.events
        if event.event_type == "record.created"
        and isinstance(event.details, dict)
        and event.details.get("schema") == LINKED_COMPLETION_SCHEMA
        and event.details.get("operation_id") == operation_id
    ]
    if len(intents) > 1 or len(completions) > 1:
        return LinkedCreateResult("AMBIGUOUS", operation_id, ("multiple linked events use this operation_id",))
    if completions and not intents:
        return LinkedCreateResult("CONFLICT", operation_id, ("completion exists without matching intent",))
    if intents:
        intent = intents[0]
        record_id, version_id = intent.record_id, intent.version_id
        expected_digest = intent.details["record_digest"]
        if intent.actor_id != actor_id:
            return LinkedCreateResult("CONFLICT", operation_id, ("actor claim differs from original intent",),
                                      record_id, version_id, intent.event_id)
        supplied_digest = initial_version_digest(record_id, version_id, content, metadata)
        if supplied_digest != expected_digest:
            return LinkedCreateResult("CONFLICT", operation_id, ("candidate content or metadata differs from original intent",),
                                      record_id, version_id, intent.event_id)
        actual_digest = _find_record_digest(store, record_id, version_id)
        if actual_digest is not None and actual_digest != expected_digest:
            return LinkedCreateResult("CONFLICT", operation_id, ("committed record digest contradicts intent",),
                                      record_id, version_id, intent.event_id)
        if actual_digest is not None and not completions:
            return LinkedCreateResult("UNCERTAIN", operation_id, ("matching record exists but completion is absent; ordinary retry cannot complete it",),
                                      record_id, version_id, intent.event_id)
        if completions:
            completion = completions[0]
            if (completion.actor_id != actor_id or completion.record_id != record_id
                    or completion.version_id != version_id
                    or completion.details.get("intent_event_id") != intent.event_id
                    or completion.details.get("record_digest") != expected_digest):
                return LinkedCreateResult("CONFLICT", operation_id, ("completion contradicts intent",),
                                          record_id, version_id, intent.event_id, completion.event_id)
            if actual_digest != expected_digest:
                return LinkedCreateResult("CONFLICT", operation_id, ("completion exists without matching committed record",),
                                          record_id, version_id, intent.event_id, completion.event_id)
            return LinkedCreateResult("CONSISTENT", operation_id, ("operation already completed and verified",),
                                      record_id, version_id, intent.event_id, completion.event_id)
    else:
        record_id = _new_candidate_id({row["record_id"] for row in store.to_snapshot()["records"]} | {operation_id})
        version_ids = {row["version_id"] for row in store.to_snapshot()["versions"]}
        version_id = _new_candidate_id(version_ids | {record_id, operation_id})
        digest = initial_version_digest(record_id, version_id, content, metadata)
        intent = ledger.append(
            "record.create_intent", actor_id, record_id=record_id, version_id=version_id,
            details={"schema": LINKED_INTENT_SCHEMA, "operation_id": operation_id, "record_digest": digest},
        )
        _fault("before_intent_write")
        write_json_atomic(ledger_path, ledger.to_snapshot())
        _fault("after_intent_write")
        ledger = EventLedger.from_snapshot(read_json_strict(ledger_path))
        if not any(event.event_id == intent.event_id for event in ledger.events):
            return LinkedCreateResult("UNCERTAIN", operation_id, ("intent read-back did not verify",), record_id, version_id, intent.event_id)
        expected_digest = digest

    # A unique intent exists and the exact candidate is absent: resume/commit it.
    current_store = load_store(store_path)
    existing_digest = _find_record_digest(current_store, record_id, version_id)
    if existing_digest is None:
        if record_id in {row["record_id"] for row in current_store.to_snapshot()["records"]}:
            return LinkedCreateResult("CONFLICT", operation_id, ("candidate record identity exists with different version evidence",),
                                      record_id, version_id, intent.event_id)
        candidate = _candidate_store(current_store, record_id, version_id, content, metadata)
        _fault("before_memory_write")
        save_store(candidate, store_path)
        _fault("after_memory_write")
    committed = load_store(store_path)
    if _find_record_digest(committed, record_id, version_id) != expected_digest:
        return LinkedCreateResult("UNCERTAIN", operation_id, ("memory commit/read-back could not be verified",),
                                  record_id, version_id, intent.event_id)

    # Completion is appended only after the committed record has been read back.
    ledger = EventLedger.from_snapshot(read_json_strict(ledger_path))
    matching = [
        event for event in ledger.events
        if event.event_type == "record.created" and isinstance(event.details, dict)
        and event.details.get("schema") == LINKED_COMPLETION_SCHEMA
        and event.details.get("operation_id") == operation_id
    ]
    if matching:
        return LinkedCreateResult("UNCERTAIN", operation_id, ("completion appeared during operation; reconcile before retry",),
                                  record_id, version_id, intent.event_id, matching[0].event_id)
    completion = ledger.append(
        "record.created", actor_id, record_id=record_id, version_id=version_id,
        details={
            "schema": LINKED_COMPLETION_SCHEMA, "operation_id": operation_id,
            "intent_event_id": intent.event_id, "record_digest": expected_digest,
        },
    )
    _fault("before_completion_write")
    write_json_atomic(ledger_path, ledger.to_snapshot())
    _fault("after_completion_write")
    verified_ledger = EventLedger.from_snapshot(read_json_strict(ledger_path))
    verified_completion = next(
        (event for event in verified_ledger.events if event.event_id == completion.event_id), None
    )
    verified_store = load_store(store_path)
    if verified_completion is None or _find_record_digest(verified_store, record_id, version_id) != expected_digest:
        return LinkedCreateResult("UNCERTAIN", operation_id, ("completion or record read-back could not be verified",),
                                  record_id, version_id, intent.event_id, completion.event_id)
    return LinkedCreateResult("CONSISTENT", operation_id, ("intent, record, and completion verified by read-back",),
                              record_id, version_id, intent.event_id, completion.event_id)
