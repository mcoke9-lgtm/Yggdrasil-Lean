"""Intake-to-understanding lifecycle adapter for YIM-001.

This adapter connects preserved work-intake packets to the Human Understanding
Register. A host supplies a Mari proposal provider; this module does not
pretend that Mari is running autonomously when no provider is configured.
"""
from __future__ import annotations

from typing import Any, Callable

from .naming import HumanUnderstandingRegister, NamingRegistryError


class UnderstandingLifecycleError(ValueError):
    """Raised when intake cannot be safely registered for human understanding."""


MariProposalProvider = Callable[[dict[str, Any]], dict[str, Any]]


class HumanUnderstandingLifecycle:
    """Coordinate stable identity creation and optional Mari naming proposals.

    The intake packet remains unchanged. This service appends a separate
    technical-identity entry and, when a provider is supplied, a proposal event.
    Persistence remains explicit: the host saves both the intake queue and
    register using their respective save functions.
    """

    def __init__(
        self,
        queue: Any,
        register: HumanUnderstandingRegister,
        *,
        proposer: MariProposalProvider | None = None,
    ) -> None:
        if not hasattr(queue, "packets"):
            raise TypeError("queue must expose an immutable packets collection")
        if not isinstance(register, HumanUnderstandingRegister):
            raise TypeError("register must be a HumanUnderstandingRegister")
        if proposer is not None and not callable(proposer):
            raise TypeError("proposer must be callable or None")
        self.queue = queue
        self.register = register
        self.proposer = proposer

    def register_packet(
        self,
        packet_id: str,
        *,
        entry_id: str,
        technical_id: str,
        purpose: str,
        event_prefix: str,
        actor_id: str = "human",
        keywords: list[str] | None = None,
        preserved_terms: list[str] | None = None,
    ) -> dict[str, Any]:
        packets = [p for p in self.queue.packets if p["packet_id"] == packet_id]
        if not packets:
            raise UnderstandingLifecycleError(f"intake packet not found: {packet_id}")
        packet = packets[0]
        if packet["placement_status"] != "awaiting_placement":
            raise UnderstandingLifecycleError(
                "only pending packets can enter the initial understanding workflow"
            )
        if not isinstance(event_prefix, str) or not event_prefix.strip():
            raise UnderstandingLifecycleError("event_prefix must be a non-empty stable identifier")

        existing = {entry["entry_id"]: entry for entry in self.register.entries()}
        if entry_id in existing:
            entry = existing[entry_id]
            if entry["technical_id"] != technical_id or entry["purpose"] != purpose:
                raise UnderstandingLifecycleError("existing entry identity conflicts with requested intake")
            created = False
        else:
            self.register.create_entry(
                entry_id=entry_id,
                technical_id=technical_id,
                purpose=purpose,
                event_id=f"{event_prefix}:entry",
                actor_id=actor_id,
                keywords=keywords or [],
                preserved_terms=preserved_terms or [],
            )
            created = True

        proposal = None
        if self.proposer is not None:
            # The provider receives a copy of descriptive metadata, never the
            # source content by default. A host may separately authorize access.
            request = {
                "entry_id": entry_id,
                "technical_id": technical_id,
                "purpose": purpose,
                "keywords": list(keywords or []),
                "preserved_terms": list(preserved_terms or []),
                "source_kind": packet["source_kind"],
                "language": packet["language"],
            }
            result = self.proposer(request)
            if not isinstance(result, dict):
                raise UnderstandingLifecycleError("Mari proposal provider must return a mapping")
            required = {
                "proposal_id", "options", "preferred_option_id",
                "competing_interpretations", "source_refs",
            }
            if set(result) != required:
                raise UnderstandingLifecycleError("Mari provider returned an invalid proposal structure")
            # Repeated processing must not append duplicate proposals.
            known = {
                proposal["proposal_id"]
                for entry in self.register.entries()
                for proposal in entry["proposals"]
            }
            if result["proposal_id"] not in known:
                proposal = self.register.propose_name(
                    entry_id=entry_id,
                    proposal_id=result["proposal_id"],
                    event_id=f"{event_prefix}:mari-proposal",
                    actor_id="mari",
                    options=result["options"],
                    preferred_option_id=result["preferred_option_id"],
                    competing_interpretations=result["competing_interpretations"],
                    source_refs=result["source_refs"],
                )
            else:
                proposal = next(
                    item for entry in self.register.entries()
                    for item in entry["proposals"]
                    if item["proposal_id"] == result["proposal_id"]
                )

        current = next(entry for entry in self.register.entries() if entry["entry_id"] == entry_id)
        return {
            "packet_id": packet_id,
            "entry_id": entry_id,
            "technical_id": technical_id,
            "entry_created": created,
            "proposal_created": proposal is not None and proposal.get("event_id") == f"{event_prefix}:mari-proposal",
            "mari_provider_configured": self.proposer is not None,
            "current_human_name": current["current_human_name"],
            "proposal_count": len(current["proposals"]),
            "decision_required_for_adoption": True,
        }
