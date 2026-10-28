"""In-memory reference contract for target-enforced recovery fencing.

This is a test model, not a production remote-service adapter. A real target
must atomically persist the highest fencing token per operation and reject
requests with a lower token in the same transaction as the protected effect.
"""
from __future__ import annotations

from dataclasses import dataclass
from threading import Lock
from typing import Literal


TargetStatus = Literal["APPLIED", "IDEMPOTENT_REPLAY", "STALE_TOKEN", "IDEMPOTENCY_CONFLICT", "INVALID"]


@dataclass(frozen=True)
class TargetResult:
    status: TargetStatus
    operation_id: str
    fencing_token: int
    idempotency_key: str
    reason: str


class InMemoryFencedTarget:
    """Small atomic model of a target that enforces fencing generations."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._highest_token: dict[str, int] = {}
        self._idempotent_results: dict[tuple[str, str], tuple[int, str]] = {}
        self._effects: list[tuple[str, int, str, str]] = []

    @property
    def effects(self) -> tuple[tuple[str, int, str, str], ...]:
        with self._lock:
            return tuple(self._effects)

    def highest_token(self, operation_id: str) -> int | None:
        with self._lock:
            return self._highest_token.get(operation_id)

    def apply(
        self, operation_id: str, fencing_token: int,
        idempotency_key: str, payload: str,
    ) -> TargetResult:
        """Atomically fence stale tokens, deduplicate exact requests, then apply."""
        if (not isinstance(operation_id, str) or not operation_id.strip()
                or not isinstance(idempotency_key, str) or not idempotency_key.strip()
                or not isinstance(payload, str)
                or not isinstance(fencing_token, int) or isinstance(fencing_token, bool)
                or fencing_token < 1):
            return TargetResult("INVALID", str(operation_id), fencing_token
                if isinstance(fencing_token, int) and not isinstance(fencing_token, bool) else 0,
                str(idempotency_key), "operation, key, payload, or token is invalid")

        with self._lock:
            highest = self._highest_token.get(operation_id, 0)
            if fencing_token < highest:
                return TargetResult("STALE_TOKEN", operation_id, fencing_token,
                    idempotency_key, f"target has already accepted token {highest}")

            idem_key = (operation_id, idempotency_key)
            prior = self._idempotent_results.get(idem_key)
            if prior is not None:
                prior_token, prior_payload = prior
                if prior_payload != payload:
                    return TargetResult("IDEMPOTENCY_CONFLICT", operation_id,
                        fencing_token, idempotency_key,
                        "idempotency key was already used with different payload")
                # A replay with an older generation is stale even if its payload matches.
                if fencing_token < prior_token:
                    return TargetResult("STALE_TOKEN", operation_id, fencing_token,
                        idempotency_key, f"request is older than token {prior_token}")
                self._highest_token[operation_id] = max(highest, fencing_token)
                return TargetResult("IDEMPOTENT_REPLAY", operation_id, fencing_token,
                    idempotency_key, "exact operation already applied")

            self._highest_token[operation_id] = fencing_token
            self._idempotent_results[idem_key] = (fencing_token, payload)
            self._effects.append((operation_id, fencing_token, idempotency_key, payload))
            return TargetResult("APPLIED", operation_id, fencing_token,
                idempotency_key, "target atomically accepted token and applied effect")
