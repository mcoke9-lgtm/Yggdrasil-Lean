"""Deterministic, side-effect-free alternative-work evaluator (AF-AWR-001 v0.1.0).

This module recommends a next action. It does not execute operations, grant
permissions, persist decisions, or claim that an alternative was completed.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


Outcome = Literal[
    "CONTINUE_ORIGINAL", "SELECT_ALTERNATIVE", "RETRY_JUSTIFIED",
    "WAIT_FOR_DEPENDENCY", "STOP_NO_ALTERNATIVE", "UNRESOLVED", "INVALID_INPUT",
]
EvaluationStatus = Literal["not_run", "passed", "failed", "inconclusive", "blocked"]
ExecutionStatus = Literal["not_attempted", "attempted", "completed", "failed", "unverified"]
PersistenceStatus = Literal[
    "not_assessed", "not_persisted", "persisted", "persistence_failed", "unknown"
]


@dataclass(frozen=True)
class Candidate:
    candidate_id: str
    description: str
    relevant: bool | None
    feasible: bool | None
    authorized: bool | None
    duplicate: bool | None
    integrity_ok: bool | None
    preserves_continuity: bool
    removes_dependency: bool
    concrete_progress: bool
    unresolved_dependencies: int = 0
    waiting_on_dependency: bool = False
    dependency_id: str | None = None


@dataclass(frozen=True)
class WorkRequest:
    request_id: str
    active_branch: str
    checkpoint_id: str
    current_goal: str
    blocked_operation: str
    observed_failure: str
    blocked: bool
    candidates: tuple[Candidate, ...] = ()
    search_scope_exhausted: bool = False
    retry_requested: bool = False
    retry_material_change: bool = False
    prior_retry_count: int = 0
    retry_budget: int = 1
    dependency_wait_is_productive: bool = False
    operation_fingerprint: str | None = None
    prior_operation_fingerprints: tuple[str, ...] = ()


@dataclass(frozen=True)
class CandidateAssessment:
    candidate_id: str
    eligible: bool
    rejected_reasons: tuple[str, ...]
    rank_key: tuple[int, int, int, int] | None


@dataclass(frozen=True)
class WorkDecision:
    request_id: str | None
    active_branch: str | None
    checkpoint_id: str | None
    evaluation_status: EvaluationStatus
    outcome: Outcome
    execution_status: ExecutionStatus
    persistence_status: PersistenceStatus
    candidate_assessments: tuple[CandidateAssessment, ...]
    recommended_candidate_ids: tuple[str, ...]
    unresolved_dependencies: tuple[str, ...]
    evidence: tuple[str, ...]
    next_step: str
    requirement_ids: tuple[str, ...] = (
        "AF-AWR-REQ-001", "AF-AWR-REQ-002", "AF-AWR-REQ-003",
        "AF-AWR-REQ-004", "AF-AWR-REQ-006", "AF-AWR-REQ-007",
        "AF-AWR-REQ-010", "AF-AWR-REQ-012", "AF-AWR-REQ-013",
        "AF-AWR-REQ-014", "AF-AWR-REQ-015", "AF-AWR-REQ-016",
        "AF-AWR-REQ-017", "AF-AWR-REQ-018", "AF-AWR-REQ-019",
    )


def _invalid(message: str, request: WorkRequest | None = None) -> WorkDecision:
    valid_request = request if isinstance(request, WorkRequest) else None
    return WorkDecision(
        request_id=valid_request.request_id if valid_request else None,
        active_branch=valid_request.active_branch if valid_request else None,
        checkpoint_id=valid_request.checkpoint_id if valid_request else None,
        evaluation_status="failed",
        outcome="INVALID_INPUT",
        execution_status="not_attempted",
        persistence_status="not_persisted",
        candidate_assessments=(),
        recommended_candidate_ids=(),
        unresolved_dependencies=(message,),
        evidence=("input_validation_failed",),
        next_step="Correct the invalid input and rerun evaluation; no operation was executed.",
    )


def _valid(request: WorkRequest) -> str | None:
    if not isinstance(request, WorkRequest):
        return "request must be a WorkRequest"
    required = {
        "request_id": request.request_id,
        "active_branch": request.active_branch,
        "checkpoint_id": request.checkpoint_id,
        "current_goal": request.current_goal,
        "blocked_operation": request.blocked_operation,
        "observed_failure": request.observed_failure,
    }
    for name, value in required.items():
        if not isinstance(value, str) or not value.strip():
            return f"{name} must be non-empty text"
    for name in ("blocked", "search_scope_exhausted", "retry_requested",
                 "retry_material_change", "dependency_wait_is_productive"):
        if not isinstance(getattr(request, name), bool):
            return f"{name} must be a boolean"
    for name in ("prior_retry_count", "retry_budget"):
        value = getattr(request, name)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return f"{name} must be a non-negative integer"
    if request.operation_fingerprint is not None and (
            not isinstance(request.operation_fingerprint, str)
            or not request.operation_fingerprint.strip()):
        return "operation_fingerprint must be non-empty text or None"
    if not isinstance(request.prior_operation_fingerprints, (tuple, list)):
        return "prior_operation_fingerprints must be a tuple or list"
    if any(not isinstance(item, str) or not item.strip()
           for item in request.prior_operation_fingerprints):
        return "prior_operation_fingerprints must contain non-empty text values"
    if not isinstance(request.candidates, (tuple, list)):
        return "candidates must be a tuple or list"
    if any(not isinstance(candidate, Candidate) for candidate in request.candidates):
        return "every candidate must be a Candidate"
    ids = [candidate.candidate_id for candidate in request.candidates]
    if any(not isinstance(candidate.candidate_id, str) or not candidate.candidate_id.strip()
           for candidate in request.candidates):
        return "candidate_id must be non-empty text"
    if len(set(ids)) != len(ids):
        return "candidate IDs must be unique"
    for candidate in request.candidates:
        if not isinstance(candidate.description, str) or not candidate.description.strip():
            return f"{candidate.candidate_id}.description must be non-empty text"
        for name in ("relevant", "feasible", "authorized", "duplicate", "integrity_ok"):
            value = getattr(candidate, name)
            if value is not None and not isinstance(value, bool):
                return f"{candidate.candidate_id}.{name} must be true, false, or unknown"
        for name in ("preserves_continuity", "removes_dependency",
                     "concrete_progress", "waiting_on_dependency"):
            if not isinstance(getattr(candidate, name), bool):
                return f"{candidate.candidate_id}.{name} must be a boolean"
        if candidate.dependency_id is not None and (
                not isinstance(candidate.dependency_id, str) or not candidate.dependency_id.strip()):
            return f"{candidate.candidate_id}.dependency_id must be non-empty text or None"
        if (not isinstance(candidate.unresolved_dependencies, int)
                or isinstance(candidate.unresolved_dependencies, bool)
                or candidate.unresolved_dependencies < 0):
            return f"{candidate.candidate_id}.unresolved_dependencies must be a non-negative integer"
    return None


def evaluate_work(request: WorkRequest) -> WorkDecision:
    """Return a deterministic recommendation without executing or persisting it."""
    invalid = _valid(request)
    if invalid:
        return _invalid(invalid, request)

    if not request.blocked:
        return WorkDecision(
            request.request_id, request.active_branch, request.checkpoint_id,
            "passed", "CONTINUE_ORIGINAL", "not_attempted", "not_persisted",
            (), (), (), ("original_operation_not_blocked",),
            "Continue the original operation; this evaluator performed no action.",
        )

    if request.retry_requested:
        fingerprint_is_new = (
            request.operation_fingerprint is not None
            and request.operation_fingerprint not in request.prior_operation_fingerprints
        )
        if (request.prior_retry_count < request.retry_budget
                and request.retry_material_change and fingerprint_is_new):
            return WorkDecision(
                request.request_id, request.active_branch, request.checkpoint_id,
                "passed", "RETRY_JUSTIFIED", "not_attempted", "not_persisted",
                (), (), (), ("retry_has_material_change", "retry_budget_available",
                             "operation_fingerprint_is_new"),
                "A single materially changed retry is justified; execute it separately and verify its result.",
            )

    assessments: list[CandidateAssessment] = []
    for candidate in request.candidates:
        rejected: list[str] = []
        gates = (
            ("relevance_unknown_or_false", candidate.relevant is True),
            ("feasibility_unknown_or_false", candidate.feasible is True),
            ("authorization_unknown_or_false", candidate.authorized is True),
            ("duplicate_or_unknown", candidate.duplicate is False),
            ("integrity_unknown_or_false", candidate.integrity_ok is True),
        )
        for reason, passed in gates:
            if not passed:
                rejected.append(reason)
        eligible = not rejected
        rank_key = (
            int(candidate.preserves_continuity),
            int(candidate.removes_dependency),
            int(candidate.concrete_progress),
            -candidate.unresolved_dependencies,
        ) if eligible else None
        assessments.append(CandidateAssessment(
            candidate.candidate_id, eligible, tuple(rejected), rank_key
        ))

    eligible = [a for a in assessments if a.eligible]
    if eligible:
        best_rank = max(a.rank_key for a in eligible if a.rank_key is not None)
        top = tuple(sorted(a.candidate_id for a in eligible if a.rank_key == best_rank))
        return WorkDecision(
            request.request_id, request.active_branch, request.checkpoint_id,
            "passed", "SELECT_ALTERNATIVE", "not_attempted", "not_persisted",
            tuple(assessments), top, (), ("eligible_candidates_ranked", "no_side_effects"),
            ("Choose among equally ranked alternatives: " + ", ".join(top)
             if len(top) > 1 else "Review the recommended alternative before executing it."),
        )

    waiting = tuple(sorted(
        (c.candidate_id, c.dependency_id) for c in request.candidates
        if c.waiting_on_dependency and c.relevant is True and c.feasible is False
        and c.integrity_ok is True and c.authorized is True and c.duplicate is False
        and c.dependency_id is not None
    ))
    if waiting and request.dependency_wait_is_productive:
        candidate_ids = tuple(candidate_id for candidate_id, _ in waiting)
        dependency_ids = tuple(sorted({dependency_id for _, dependency_id in waiting}))
        return WorkDecision(
            request.request_id, request.active_branch, request.checkpoint_id,
            "passed", "WAIT_FOR_DEPENDENCY", "not_attempted", "not_persisted",
            tuple(assessments), candidate_ids, dependency_ids,
            ("dependency_wait_is_productive", "dependency_ids_explicit"),
            "Wait for the named dependency; do independent work only if separately identified.",
        )

    if request.search_scope_exhausted:
        return WorkDecision(
            request.request_id, request.active_branch, request.checkpoint_id,
            "passed", "STOP_NO_ALTERNATIVE", "not_attempted", "not_persisted",
            tuple(assessments), (), (), ("eligible_candidate_search_exhausted",),
            "Stop this branch of work and report the blocker; do not repeat unchanged attempts.",
        )

    return WorkDecision(
        request.request_id, request.active_branch, request.checkpoint_id,
        "inconclusive", "UNRESOLVED", "not_attempted", "not_persisted",
        tuple(assessments), (), (), ("search_scope_not_exhausted",),
        "Expand or clarify the search scope; do not claim that no alternative exists.",
    )
