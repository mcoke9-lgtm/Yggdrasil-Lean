"""Stable JSON-compatible reports for resolver, audit, and work decisions.

Serialization is local and side-effect-free. These helpers do not persist reports
or claim that execution/persistence occurred.
"""
from __future__ import annotations

from dataclasses import asdict
from typing import Any

from .alternative_work import WorkDecision
from .audit import SnapshotAudit
from .resolver import ResolutionResult


def _report(schema_version: str, value: Any, expected_type: type) -> dict[str, Any]:
    if not isinstance(value, expected_type):
        raise TypeError(f"{schema_version} requires {expected_type.__name__}")
    return {"schema_version": schema_version, **asdict(value)}


def resolution_report(result: ResolutionResult) -> dict[str, Any]:
    """Serialize a resolved record without implying snapshot completeness."""
    return _report("af.resolution.v1", result, ResolutionResult)


def snapshot_audit_report(result: SnapshotAudit) -> dict[str, Any]:
    """Serialize audit status, findings, and the caller-declared coverage state."""
    report = _report("af.snapshot-audit.v1", result, SnapshotAudit)
    report["status"] = result.status
    report["error_count"] = result.error_count
    report["unclassified_count"] = result.unclassified_count
    return report


def work_decision_report(result: WorkDecision) -> dict[str, Any]:
    """Serialize recommendation separately from execution and persistence status."""
    return _report("af.work-decision.v1", result, WorkDecision)
