"""THO-015 Probe 4: identifier compatibility and case-distinct record IDs."""
from __future__ import annotations
import json, platform, sys
from yim001 import MemoryStore, ValidationError
from yim001.provenance import EventLedger, ProvenanceError


def memory_snapshot(record_id, version_id):
    return {
        "format": MemoryStore.SNAPSHOT_FORMAT,
        "format_version": MemoryStore.SNAPSHOT_VERSION,
        "records": [{"record_id": record_id, "version_ids": [version_id]}],
        "versions": [{
            "version_id": version_id, "record_id": record_id,
            "parents": [], "content": {"probe": "THO-015-Probe-4"},
            "metadata": None,
        }],
        "conflicts": [],
    }


def two_record_snapshot(record_ids):
    """Build two independent valid records in one snapshot."""
    records, versions = [], []
    for index, record_id in enumerate(record_ids):
        version_id = f"case-collision-version-{index}"
        records.append({"record_id": record_id, "version_ids": [version_id]})
        versions.append({
            "version_id": version_id,
            "record_id": record_id,
            "parents": [],
            "content": {"record": index, "probe": "case-distinct-record-ids"},
            "metadata": None,
        })
    return {
        "format": MemoryStore.SNAPSHOT_FORMAT,
        "format_version": MemoryStore.SNAPSHOT_VERSION,
        "records": records,
        "versions": versions,
        "conflicts": [],
    }


def main():
    cases = [
        ("reserved_windows_device", "CON", "CON-version", "ACCEPT"),
        ("reserved_dos_aux", "AUX", "AUX-version", "ACCEPT"),
        ("dot_segment", ".", "dot-version", "ACCEPT"),
        ("dotdot_segment", "..", "dotdot-version", "ACCEPT"),
        ("path_separator", "../outside", "path-version", "ACCEPT"),
        ("empty_identifier", "", "empty-version", "REJECT"),
    ]
    results = []
    for name, rid, vid, expected in cases:
        try:
            restored = MemoryStore.from_snapshot(memory_snapshot(rid, vid))
            actual = "ACCEPT" if restored.versions(rid)[0].version_id == vid else "UNEXPECTED"
        except (ValidationError, ValueError, TypeError, KeyError):
            actual = "REJECT"
        results.append({"case": name, "expected": expected, "actual": actual})

    # Critical correction: two distinct record IDs differing only by case
    # coexist in the SAME snapshot and must both survive import/round-trip.
    case_ids = ["Legacy-ID", "legacy-id"]
    try:
        case_store = MemoryStore.from_snapshot(two_record_snapshot(case_ids))
        round_trip = MemoryStore.from_snapshot(case_store.to_snapshot())
        case_record_ids = [row["record_id"] for row in round_trip.to_snapshot()["records"]]
        case_distinct_records = (
            len(case_record_ids) == 2
            and set(case_record_ids) == set(case_ids)
            and round_trip.versions("Legacy-ID")[0].record_id == "Legacy-ID"
            and round_trip.versions("legacy-id")[0].record_id == "legacy-id"
        )
        case_distinct_record_result = "ACCEPT_AND_ROUND_TRIP" if case_distinct_records else "UNEXPECTED"
    except (ValidationError, ValueError, TypeError, KeyError):
        case_distinct_record_result = "REJECT"

    # Exact duplicate record identity remains rejected.
    duplicate = memory_snapshot("same", "v1")
    duplicate["records"].append({"record_id": "same", "version_ids": ["v2"]})
    duplicate["versions"].append({
        "version_id": "v2", "record_id": "same", "parents": ["v1"],
        "content": {}, "metadata": None,
    })
    try:
        MemoryStore.from_snapshot(duplicate)
        duplicate_record = "ACCEPT"
    except ValidationError:
        duplicate_record = "REJECT"

    # Event IDs are a separate namespace; verify exact-string case distinction.
    ledger = EventLedger()
    try:
        ledger.append("record.created", "probe", record_id="Legacy-ID",
                      version_id="legacy-id", event_id="Legacy-ID",
                      timestamp="2026-11-10T12:00:00Z")
        ledger.append("record.revised", "probe", record_id="legacy-id",
                      version_id="Legacy-ID", event_id="legacy-id",
                      timestamp="2026-11-10T12:01:00Z")
        event_case = "ACCEPT"
    except ProvenanceError:
        event_case = "REJECT"

    checks_ok = (
        all(row["actual"] == row["expected"] for row in results)
        and case_distinct_record_result == "ACCEPT_AND_ROUND_TRIP"
        and duplicate_record == "REJECT"
        and event_case == "ACCEPT"
    )
    summary = {
        "probe": "THO-015-PROBE-4",
        "python": sys.version,
        "platform": platform.platform(),
        "observations": results,
        "case_distinct_record_ids_same_snapshot": case_ids,
        "case_distinct_record_result": case_distinct_record_result,
        "round_trip_record_ids": case_record_ids if case_distinct_record_result == "ACCEPT_AND_ROUND_TRIP" else [],
        "exact_duplicate_record_id": duplicate_record,
        "case_distinct_event_ids": event_case,
        "overall": "OBSERVED" if checks_ok else "REVIEW",
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print("THO015_PROBE4_RESULT=" + summary["overall"])
    return 0 if checks_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
