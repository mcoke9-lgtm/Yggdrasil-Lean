"""Append-only human-understanding and naming proposals for YIM-001.

This module records technical identities, human-readable interpretations,
Character proposals, and separately authorized naming decisions. It does not
authenticate callers or make a Character autonomous.
"""
from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path
import tempfile
from typing import Any


class NamingRegistryError(ValueError):
    """Raised when a naming event or registry snapshot is invalid."""


class HumanUnderstandingRegister:
    FORMAT = "YIM-001-Human-Understanding-Register"
    FORMAT_VERSION = 1
    EVENT_TYPES = {"entry.created", "name.proposed", "name.decision"}
    DECISIONS = {"adopted", "declined", "deferred", "superseded"}

    def __init__(self, registry_id: str, events: list[dict[str, Any]] | None = None):
        self._require_text(registry_id, "registry_id")
        self.registry_id = registry_id
        self._events: list[dict[str, Any]] = []
        if events is not None:
            self._validate_and_load(events)

    @staticmethod
    def _require_text(value: Any, field: str) -> None:
        if not isinstance(value, str) or not value.strip():
            raise NamingRegistryError(f"{field} must be a non-empty string")

    @property
    def events(self) -> list[dict[str, Any]]:
        return deepcopy(self._events)

    def _append(self, event_id: str, event_type: str, actor_id: str,
                payload: dict[str, Any]) -> dict[str, Any]:
        self._require_text(event_id, "event_id")
        self._require_text(actor_id, "actor_id")
        if event_type not in self.EVENT_TYPES:
            raise NamingRegistryError("unsupported event_type")
        if any(event["event_id"] == event_id for event in self._events):
            raise NamingRegistryError(f"duplicate event_id: {event_id}")
        event = {
            "sequence": len(self._events) + 1,
            "event_id": event_id,
            "event_type": event_type,
            "actor_id": actor_id,
            "payload": deepcopy(payload),
        }
        candidate = self._events + [event]
        self._validate_and_load(candidate)
        return deepcopy(self._events[-1])

    def _validate_and_load(self, events: list[dict[str, Any]]) -> None:
        if not isinstance(events, list):
            raise NamingRegistryError("events must be a list")
        seen_event_ids: set[str] = set()
        entries: dict[str, dict[str, Any]] = {}
        proposals: dict[str, dict[str, Any]] = {}
        for index, event in enumerate(events, start=1):
            if not isinstance(event, dict) or set(event) != {
                "sequence", "event_id", "event_type", "actor_id", "payload"
            }:
                raise NamingRegistryError("invalid event structure")
            if type(event["sequence"]) is not int or event["sequence"] != index:
                raise NamingRegistryError("event sequence must be contiguous and one-based")
            self._require_text(event["event_id"], "event_id")
            self._require_text(event["actor_id"], "actor_id")
            if event["event_id"] in seen_event_ids:
                raise NamingRegistryError("duplicate event_id")
            seen_event_ids.add(event["event_id"])
            event_type = event["event_type"]
            payload = event["payload"]
            if event_type not in self.EVENT_TYPES or not isinstance(payload, dict):
                raise NamingRegistryError("unsupported event or invalid payload")

            if event_type == "entry.created":
                required = {"entry_id", "technical_id", "purpose", "keywords", "preserved_terms"}
                if set(payload) != required:
                    raise NamingRegistryError("invalid entry.created payload")
                for field in ("entry_id", "technical_id", "purpose"):
                    self._require_text(payload[field], field)
                for field in ("keywords", "preserved_terms"):
                    if not isinstance(payload[field], list) or not all(
                        isinstance(item, str) and item.strip() for item in payload[field]
                    ):
                        raise NamingRegistryError(f"{field} must be a list of non-empty strings")
                if payload["entry_id"] in entries:
                    raise NamingRegistryError("duplicate entry_id")
                if any(row["technical_id"] == payload["technical_id"] for row in entries.values()):
                    raise NamingRegistryError("duplicate technical_id")
                entries[payload["entry_id"]] = payload

            elif event_type == "name.proposed":
                required = {
                    "entry_id", "proposal_id", "proposer_role", "preferred_option_id",
                    "options", "competing_interpretations", "source_refs"
                }
                if set(payload) != required:
                    raise NamingRegistryError("invalid name.proposed payload")
                if payload["entry_id"] not in entries:
                    raise NamingRegistryError("proposal references unknown entry")
                self._require_text(payload["proposal_id"], "proposal_id")
                self._require_text(payload["proposer_role"], "proposer_role")
                if payload["proposer_role"] != "Mari":
                    raise NamingRegistryError("naming proposals in this protocol must be attributed to Mari")
                if payload["proposal_id"] in proposals:
                    raise NamingRegistryError("duplicate proposal_id")
                if not isinstance(payload["options"], list) or len(payload["options"]) < 2:
                    raise NamingRegistryError("a proposal must preserve at least two alternatives")
                option_ids: set[str] = set()
                for option in payload["options"]:
                    if not isinstance(option, dict) or set(option) != {
                        "option_id", "human_name", "meaning", "strengths", "limitations"
                    }:
                        raise NamingRegistryError("invalid naming option")
                    for field in ("option_id", "human_name", "meaning"):
                        self._require_text(option[field], field)
                    if option["option_id"] in option_ids:
                        raise NamingRegistryError("duplicate option_id")
                    option_ids.add(option["option_id"])
                    for field in ("strengths", "limitations"):
                        if not isinstance(option[field], list) or not all(
                            isinstance(item, str) and item.strip() for item in option[field]
                        ):
                            raise NamingRegistryError(f"{field} must be a list of non-empty strings")
                if payload["preferred_option_id"] not in option_ids:
                    raise NamingRegistryError("preferred_option_id must identify an option")
                if not isinstance(payload["competing_interpretations"], list) or not all(
                    isinstance(item, str) and item.strip()
                    for item in payload["competing_interpretations"]
                ):
                    raise NamingRegistryError("competing_interpretations must be a list of non-empty strings")
                if not isinstance(payload["source_refs"], list) or not all(
                    isinstance(item, str) and item.strip() for item in payload["source_refs"]
                ):
                    raise NamingRegistryError("source_refs must be a list of non-empty strings")
                proposals[payload["proposal_id"]] = payload

            elif event_type == "name.decision":
                required = {
                    "entry_id", "proposal_id", "decision", "selected_option_id",
                    "authorized_by", "authority_role", "human_authorized"
                }
                if set(payload) != required:
                    raise NamingRegistryError("invalid name.decision payload")
                self._require_text(payload["entry_id"], "entry_id")
                self._require_text(payload["proposal_id"], "proposal_id")
                self._require_text(payload["authorized_by"], "authorized_by")
                if payload["entry_id"] not in entries or payload["proposal_id"] not in proposals:
                    raise NamingRegistryError("decision references unknown entry or proposal")
                if proposals[payload["proposal_id"]]["entry_id"] != payload["entry_id"]:
                    raise NamingRegistryError("decision proposal belongs to a different entry")
                if payload["decision"] not in self.DECISIONS:
                    raise NamingRegistryError("unsupported naming decision")
                if payload["authority_role"] not in {"human", "Týr"}:
                    raise NamingRegistryError("authority_role must be human or Týr")
                if type(payload["human_authorized"]) is not bool:
                    raise NamingRegistryError("human_authorized must be a boolean assertion")
                if payload["decision"] == "adopted":
                    if payload["human_authorized"] is not True:
                        raise NamingRegistryError("adoption requires explicit human authorization")
                    option_ids = {
                        option["option_id"]
                        for option in proposals[payload["proposal_id"]]["options"]
                    }
                    if payload["selected_option_id"] not in option_ids:
                        raise NamingRegistryError("adoption must select an option from the proposal")
                elif payload["selected_option_id"] is not None:
                    option_ids = {
                        option["option_id"]
                        for option in proposals[payload["proposal_id"]]["options"]
                    }
                    if payload["selected_option_id"] not in option_ids:
                        raise NamingRegistryError("selected_option_id is not part of the proposal")
        self._events = deepcopy(events)

    def create_entry(self, *, entry_id: str, technical_id: str, purpose: str,
                     event_id: str, actor_id: str, keywords: list[str] | None = None,
                     preserved_terms: list[str] | None = None) -> dict[str, Any]:
        return self._append(event_id, "entry.created", actor_id, {
            "entry_id": entry_id,
            "technical_id": technical_id,
            "purpose": purpose,
            "keywords": [] if keywords is None else keywords,
            "preserved_terms": [] if preserved_terms is None else preserved_terms,
        })

    def propose_name(self, *, entry_id: str, proposal_id: str, event_id: str,
                     actor_id: str, options: list[dict[str, Any]],
                     preferred_option_id: str,
                     competing_interpretations: list[str],
                     source_refs: list[str] | None = None,
                     proposer_role: str = "Mari") -> dict[str, Any]:
        return self._append(event_id, "name.proposed", actor_id, {
            "entry_id": entry_id,
            "proposal_id": proposal_id,
            "proposer_role": proposer_role,
            "preferred_option_id": preferred_option_id,
            "options": options,
            "competing_interpretations": competing_interpretations,
            "source_refs": [] if source_refs is None else source_refs,
        })

    def decide_name(self, *, entry_id: str, proposal_id: str, event_id: str,
                    actor_id: str, decision: str, authorized_by: str,
                    authority_role: str, human_authorized: bool,
                    selected_option_id: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "name.decision", actor_id, {
            "entry_id": entry_id,
            "proposal_id": proposal_id,
            "decision": decision,
            "selected_option_id": selected_option_id,
            "authorized_by": authorized_by,
            "authority_role": authority_role,
            "human_authorized": human_authorized,
        })

    def entries(self) -> list[dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}
        proposals: dict[str, dict[str, Any]] = {}
        for event in self._events:
            payload = event["payload"]
            if event["event_type"] == "entry.created":
                result[payload["entry_id"]] = {
                    **deepcopy(payload),
                    "proposals": [],
                    "decisions": [],
                    "current_human_name": None,
                }
            elif event["event_type"] == "name.proposed":
                proposal = {"event_id": event["event_id"], **deepcopy(payload)}
                proposals[payload["proposal_id"]] = proposal
                result[payload["entry_id"]]["proposals"].append(proposal)
            elif event["event_type"] == "name.decision":
                decision = {"event_id": event["event_id"], **deepcopy(payload)}
                result[payload["entry_id"]]["decisions"].append(decision)
                if payload["decision"] == "adopted":
                    proposal = proposals[payload["proposal_id"]]
                    selected = next(
                        option for option in proposal["options"]
                        if option["option_id"] == payload["selected_option_id"]
                    )
                    result[payload["entry_id"]]["current_human_name"] = selected["human_name"]
                elif payload["decision"] == "superseded":
                    result[payload["entry_id"]]["current_human_name"] = None
        return list(result.values())

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "registry_id": self.registry_id,
            "events": self.events,
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "HumanUnderstandingRegister":
        if not isinstance(snapshot, dict) or set(snapshot) != {
            "format", "format_version", "registry_id", "events"
        }:
            raise NamingRegistryError("invalid register snapshot structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] != cls.FORMAT_VERSION:
            raise NamingRegistryError("unsupported register format or version")
        return cls(snapshot["registry_id"], snapshot["events"])


def save_register(register: HumanUnderstandingRegister, path: str | Path) -> None:
    """Atomically write a portable JSON snapshot.

    This protects against a partially written single-process file update. It
    does not provide multi-writer compare-and-append or cross-device sync.
    """
    if not isinstance(register, HumanUnderstandingRegister):
        raise TypeError("register must be a HumanUnderstandingRegister")
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        encoded = json.dumps(
            register.to_snapshot(), ensure_ascii=False, allow_nan=False,
            sort_keys=True, indent=2,
        ) + "\n"
    except (TypeError, ValueError, UnicodeError) as exc:
        raise NamingRegistryError(f"register cannot be serialized: {exc}") from exc
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n",
            dir=target.parent, prefix=f".{target.name}.", suffix=".tmp",
            delete=False,
        ) as handle:
            temporary_name = handle.name
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, target)
    finally:
        if temporary_name is not None and os.path.exists(temporary_name):
            os.unlink(temporary_name)


def load_register(path: str | Path) -> HumanUnderstandingRegister:
    """Load and structurally validate a saved register snapshot."""
    try:
        with Path(path).open("r", encoding="utf-8") as handle:
            snapshot = json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise NamingRegistryError(f"register could not be read: {exc}") from exc
    return HumanUnderstandingRegister.from_snapshot(snapshot)
