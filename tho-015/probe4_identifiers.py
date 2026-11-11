"""THO-015 Probe 4: identifier collision and legacy-identifier observations."""
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

def main():
    cases = [
        ("case_distinct", "Legacy-ID", "legacy-id", "ACCEPT"),
        ("reserved_windows_device", "CON", "CON-version", "ACCEPT"),
        ("reserved_dos_aux", "AUX", "AUX-version", "ACCEPT"),
        ("dot_segment", ".", "dot-version", "ACCEPT"),
        ("dotdot_segment", "..", "dotdot-version", "ACCEPT"),
        ("path_separator", "../outside", "path-version", "ACCEPT"),
        ("empty_identifier", "", "empty-version", "REJECT"),
    ]
    results=[]
    for name, rid, vid, expected in cases:
        try:
            restored=MemoryStore.from_snapshot(memory_snapshot(rid,vid))
            actual="ACCEPT" if restored.versions(rid)[0].version_id==vid else "UNEXPECTED"
        except (ValidationError, ValueError, TypeError, KeyError) as e:
            actual="REJECT"
        results.append({"case":name,"expected_observation":expected,"actual":actual})
    # Exact duplicate record IDs and version IDs must remain rejected.
    duplicate=memory_snapshot("same","v1")
    duplicate["records"].append({"record_id":"same","version_ids":["v2"]})
    duplicate["versions"].append({"version_id":"v2","record_id":"same","parents":["v1"],"content":{},"metadata":None})
    try:
        MemoryStore.from_snapshot(duplicate)
        duplicate_record="ACCEPT"
    except ValidationError:
        duplicate_record="REJECT"
    # Provenance event identifiers are opaque non-empty strings, unique by exact
    # comparison; they are a separate namespace from record/version IDs.
    ledger=EventLedger()
    try:
        ledger.append("record.created","probe",record_id="Legacy-ID",version_id="legacy-id",
                      event_id="Legacy-ID",timestamp="2026-11-10T12:00:00Z")
        ledger.append("record.revised","probe",record_id="legacy-id",version_id="Legacy-ID",
                      event_id="legacy-id",timestamp="2026-11-10T12:01:00Z")
        event_case="ACCEPT"
    except ProvenanceError:
        event_case="REJECT"
    summary={
        "probe":"THO-015-PROBE-4","python":sys.version,"platform":platform.platform(),
        "observations":results,"exact_duplicate_record_id":duplicate_record,
        "case_distinct_event_ids":event_case,
        "overall":"OBSERVED" if all(x["actual"]==x["expected_observation"] for x in results)
                    and duplicate_record=="REJECT" and event_case=="ACCEPT" else "REVIEW",
    }
    print(json.dumps(summary,indent=2,sort_keys=True))
    print("THO015_PROBE4_RESULT="+summary["overall"])
    return 0 if summary["overall"]=="OBSERVED" else 1

if __name__=="__main__":
    raise SystemExit(main())
