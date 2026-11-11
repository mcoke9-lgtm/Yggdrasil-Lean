"""THO-015 Probe 2: malformed-history fail-closed checks.

Test-only harness. Does not modify YIM-001 source or production data.
"""
from __future__ import annotations

import copy
import json
import math
import os
import platform
import sys
import traceback

from yim001.provenance import EventLedger, ProvenanceError
from yim001.reconcile import CreationEvidence, Outcome, reconcile_creation_event


def base_snapshot():
    ledger = EventLedger()
    ledger.append(
        "record.created", "system-a", record_id="record-target",
        version_id="version-1", timestamp="2026-11-10T12:00:00Z",
    )
    ledger.append(
        "record.revised", "system-a", record_id="record-other",
        version_id="version-2", timestamp="2026-11-10T12:01:00Z",
    )
    return ledger.to_snapshot()


def malformed_cases():
    cases = []
    mutations = [
        ("sequence_boolean", lambda e: e.__setitem__("sequence", True)),
        ("sequence_string", lambda e: e.__setitem__("sequence", "1")),
        ("event_type_list", lambda e: e.__setitem__("event_type", ["record.created"])),
        ("actor_id_object", lambda e: e.__setitem__("actor_id", {"actor": "x"})),
        ("timestamp_integer", lambda e: e.__setitem__("timestamp", 123)),
        ("record_id_list", lambda e: e.__setitem__("record_id", ["record-target"])),
        ("details_non_json_number", lambda e: e.__setitem__("details", float("nan"))),
        ("previous_digest_list", lambda e: e.__setitem__("previous_digest", ["0" * 64])),
        ("digest_integer", lambda e: e.__setitem__("digest", 7)),
        ("missing_required_field", lambda e: e.pop("actor_id")),
        ("unknown_extra_field", lambda e: e.__setitem__("unexpected", True)),
    ]
    for name, mutate in mutations:
        snapshot = base_snapshot()
        mutate(snapshot["events"][1])
        cases.append((name, snapshot))
    return cases


def main():
    results = []
    for name, snapshot in malformed_cases():
        try:
            EventLedger.from_snapshot(snapshot)
            results.append({"case": name, "result": "ACCEPTED"})
        except (ProvenanceError, ValueError, TypeError, KeyError) as exc:
            results.append({
                "case": name,
                "result": "REJECTED",
                "exception": type(exc).__name__,
                "message": str(exc)[:240],
            })
        except Exception as exc:
            results.append({
                "case": name,
                "result": "UNEXPECTED_EXCEPTION",
                "exception": type(exc).__name__,
                "message": str(exc)[:240],
            })

    # User-adopted precedence: validate complete history first; any malformed
    # event anywhere makes the reconciliation outcome UNCERTAIN, even if
    # duplicate matching events would otherwise produce AMBIGUOUS.
    good = CreationEvidence("event-1", "record-target", "create", "sha256:abc", 0)
    duplicate = CreationEvidence("event-1", "record-target", "create", "sha256:abc", 1)
    precedence = reconcile_creation_event(
        expected_event_id="event-1",
        expected_record_id="record-target",
        expected_creation_type="create",
        expected_record_digest="sha256:abc",
        evidence=[good, duplicate],
        history_state="malformed",
    )
    results.append({
        "case": "malformed_history_precedes_duplicate_ambiguity",
        "result": precedence.outcome.value,
        "expected": Outcome.UNCERTAIN.value,
        "findings": list(precedence.findings),
    })

    accepted = [r["case"] for r in results if r["result"] == "ACCEPTED"]
    unexpected = [r for r in results if r["result"] == "UNEXPECTED_EXCEPTION"]
    precedence_ok = precedence.outcome is Outcome.UNCERTAIN
    summary = {
        "probe": "THO-015-PROBE-2",
        "python": sys.version,
        "platform": platform.platform(),
        "malformed_case_count": len(malformed_cases()),
        "malformed_snapshots_accepted": accepted,
        "unexpected_exceptions": unexpected,
        "precedence_actual": precedence.outcome.value,
        "precedence_expected": Outcome.UNCERTAIN.value,
        "precedence_pass": precedence_ok,
        "malformed_snapshot_rejections": results[:-1],
        "overall": "PASS" if not accepted and not unexpected and precedence_ok else "FAIL",
    }
    print(json.dumps(summary, indent=2, sort_keys=True, default=str))
    print("THO015_PROBE2_RESULT=" + summary["overall"])
    return 0 if summary["overall"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
