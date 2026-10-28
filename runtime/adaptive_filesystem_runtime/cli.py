"""JSON-over-stdin command line for local, side-effect-free runtime operations."""
from __future__ import annotations

import argparse
import json
import sys
from typing import Any

from .alternative_work import Candidate, WorkRequest, evaluate_work
from .audit import audit_snapshot
from .reporting import resolution_report, snapshot_audit_report, work_decision_report
from .recovery import RecoveryFacts, decide_recovery
from .recovery_gate import gate_retry
from .resolver import Record, ResolutionError, resolve_history


def _record(value: Any) -> Record:
    if not isinstance(value, dict):
        raise ValueError("each records item must be a JSON object")
    allowed = {
        "record_id", "branch_id", "sequence", "parent_record_id",
        "source_branch_id", "source_anchor_id", "revision_of_id",
    }
    extra = sorted(set(value) - allowed)
    if extra:
        raise ValueError(f"unsupported record fields: {', '.join(extra)}")
    try:
        return Record(**value)
    except TypeError as exc:
        raise ValueError(f"invalid record fields: {exc}") from exc


def _read_json() -> dict[str, Any]:
    value = json.load(sys.stdin)
    if not isinstance(value, dict):
        raise ValueError("input must be a JSON object")
    return value


def _records(payload: dict[str, Any]) -> list[Record]:
    values = payload.get("records")
    if not isinstance(values, list):
        raise ValueError("records must be a JSON array")
    return [_record(value) for value in values]


def _request(value: Any) -> WorkRequest:
    if not isinstance(value, dict):
        raise ValueError("request must be a JSON object")
    raw = dict(value)
    candidates = raw.get("candidates", [])
    if not isinstance(candidates, list):
        raise ValueError("request.candidates must be a JSON array")
    try:
        raw["candidates"] = tuple(Candidate(**candidate) for candidate in candidates)
        return WorkRequest(**raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid work request: {exc}") from exc


def _recovery_facts(value: Any) -> RecoveryFacts:
    if not isinstance(value, dict):
        raise ValueError("recovery_facts must be a JSON object")
    try:
        return RecoveryFacts(**value)
    except TypeError as exc:
        raise ValueError(f"invalid recovery facts: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="af-runtime",
        description="Read-only Adaptive Filesystem runtime. JSON input comes from stdin.",
    )
    parser.add_argument("command", choices=("audit", "resolve", "evaluate", "recover-evaluate"))
    args = parser.parse_args(argv)

    try:
        payload = _read_json()
        if args.command == "audit":
            result = audit_snapshot(_records(payload), coverage=payload.get("coverage", "unknown"))
            print(json.dumps(snapshot_audit_report(result), sort_keys=True, indent=2))
            return 1 if result.status == "failed" else 0
        if args.command == "resolve":
            record_id = payload.get("record_id")
            if not isinstance(record_id, str) or not record_id:
                raise ValueError("record_id must be non-empty text")
            result = resolve_history(_records(payload), record_id)
            print(json.dumps(resolution_report(result), sort_keys=True, indent=2))
            return 0
        decision = evaluate_work(_request(payload.get("request")))
        if args.command == "recover-evaluate":
            recovery = decide_recovery(_recovery_facts(payload.get("recovery_facts")))
            gated = gate_retry(decision, recovery)
            report = {
                "schema_version": "af.recovery-gated-work.v1",
                "work_decision": work_decision_report(decision),
                "recovery_decision": {
                    "outcome": recovery.outcome,
                    "reason": recovery.reason,
                },
                "retry_gate": {
                    "outcome": gated.outcome,
                    "retry_allowed": gated.retry_allowed,
                    "reason": gated.reason,
                },
                "execution_status": "not_attempted",
                "persistence_status": "not_persisted",
            }
            print(json.dumps(report, sort_keys=True, indent=2))
            return 2 if decision.outcome == "INVALID_INPUT" or recovery.outcome == "STOP_CONFLICT" else 0
        print(json.dumps(work_decision_report(decision), sort_keys=True, indent=2))
        return 2 if decision.outcome == "INVALID_INPUT" else 0
    except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        report = {"error": type(exc).__name__, "detail": str(exc)}
        if isinstance(exc, ResolutionError):
            report["code"] = exc.code
            report["detail"] = exc.detail
        print(json.dumps(report, sort_keys=True), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
