"""Reference implementation for Adaptive Filesystem review-history resolution."""
from .resolver import Record, ResolutionError, ResolutionResult, resolve_history
from .audit import SnapshotAudit, SnapshotFinding, audit_snapshot
from .alternative_work import Candidate, WorkDecision, WorkRequest, evaluate_work

__all__ = [
    "Record", "ResolutionError", "ResolutionResult", "resolve_history",
    "SnapshotAudit", "SnapshotFinding", "audit_snapshot",
    "Candidate", "WorkDecision", "WorkRequest", "evaluate_work",
]
