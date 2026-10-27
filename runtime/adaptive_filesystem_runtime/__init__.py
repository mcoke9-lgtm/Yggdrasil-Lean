"""Reference implementation for Adaptive Filesystem review-history resolution."""
from .resolver import Record, ResolutionError, ResolutionResult, resolve_history
from .audit import SnapshotAudit, SnapshotFinding, audit_snapshot
from .alternative_work import Candidate, WorkDecision, WorkRequest, evaluate_work
from .reporting import resolution_report, snapshot_audit_report, work_decision_report

__all__ = [
    "Record", "ResolutionError", "ResolutionResult", "resolve_history",
    "SnapshotAudit", "SnapshotFinding", "audit_snapshot",
    "Candidate", "WorkDecision", "WorkRequest", "evaluate_work",
    "resolution_report", "snapshot_audit_report", "work_decision_report",
]
