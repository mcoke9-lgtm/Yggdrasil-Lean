"""Conditional publication contract for branch-ref updates.

This module defines a narrow adapter boundary; it does not implement a GitHub
client. The remote adapter must perform an atomic compare-and-swap against
expected_head. Ambiguous transport failures are reconciled by re-reading the
ref and are never automatically retried.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol


class RefUpdateRejected(Exception):
    """The remote explicitly rejected an update because the head was stale."""


class RefUpdateAdapter(Protocol):
    def read_head(self, branch: str) -> str:
        """Return the current commit SHA for branch."""

    def compare_and_swap(
        self, branch: str, expected_head: str, desired_head: str
    ) -> None:
        """Atomically move branch only if its current head equals expected_head.

        Raise RefUpdateRejected for a confirmed stale-head rejection. Other
        exceptions are treated as ambiguous until the ref is re-read.
        """


class PublicationStatus(str, Enum):
    PUBLISHED = "PUBLISHED"
    STALE_HEAD = "STALE_HEAD"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class PublicationResult:
    status: PublicationStatus
    branch: str
    expected_head: str
    desired_head: str
    observed_head: str | None
    detail: str


def publish_once(
    adapter: RefUpdateAdapter,
    branch: str,
    expected_head: str,
    desired_head: str,
) -> PublicationResult:
    """Attempt one conditional update; never retry an uncertain publication."""
    if not all(isinstance(value, str) and value.strip() for value in
               (branch, expected_head, desired_head)):
        raise ValueError("branch and commit SHAs must be non-empty strings")
    if expected_head == desired_head:
        raise ValueError("desired_head must differ from expected_head")

    try:
        adapter.compare_and_swap(branch, expected_head, desired_head)
    except RefUpdateRejected:
        observed = adapter.read_head(branch)
        return PublicationResult(
            PublicationStatus.STALE_HEAD, branch, expected_head, desired_head,
            observed, "remote explicitly rejected stale expected head",
        )
    except Exception as exc:
        # The request may have reached the server despite a transport error.
        try:
            observed = adapter.read_head(branch)
        except Exception:
            observed = None
        if observed == desired_head:
            return PublicationResult(
                PublicationStatus.PUBLISHED, branch, expected_head, desired_head,
                observed, "desired head observed after ambiguous update response",
            )
        if observed is not None and observed != expected_head:
            return PublicationResult(
                PublicationStatus.STALE_HEAD, branch, expected_head, desired_head,
                observed, "head changed during ambiguous update; do not retry blindly",
            )
        return PublicationResult(
            PublicationStatus.UNKNOWN, branch, expected_head, desired_head,
            observed, f"update outcome unresolved ({type(exc).__name__}); no retry",
        )

    observed = adapter.read_head(branch)
    if observed == desired_head:
        return PublicationResult(
            PublicationStatus.PUBLISHED, branch, expected_head, desired_head,
            observed, "conditional update confirmed by read-back",
        )
    if observed != expected_head:
        return PublicationResult(
            PublicationStatus.STALE_HEAD, branch, expected_head, desired_head,
            observed, "head differs from expected; do not retry blindly",
        )
    return PublicationResult(
        PublicationStatus.UNKNOWN, branch, expected_head, desired_head,
        observed, "update returned without changing the observed head",
    )
