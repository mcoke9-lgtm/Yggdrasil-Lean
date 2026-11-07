"""Synthetic interrupted-transfer recovery contract.

No network, provider, or persistent storage is used. The contract models a
source snapshot and a partial destination receipt, preserving the source and
making missing data explicit.
"""
from copy import deepcopy


def inspect_transfer(source_records, received_records):
    """Classify a received subset against the source snapshot."""
    source = deepcopy(source_records)
    received = deepcopy(received_records)
    source_ids = [r.get("record_id") for r in source if isinstance(r, dict)]
    received_ids = [r.get("record_id") for r in received if isinstance(r, dict)]
    if len(source_ids) != len(source) or any(not x for x in source_ids):
        raise ValueError("source_record_identity_invalid")
    if len(received_ids) != len(received) or any(not x for x in received_ids):
        raise ValueError("received_record_identity_invalid")
    if len(set(source_ids)) != len(source_ids):
        raise ValueError("duplicate_source_record")
    if len(set(received_ids)) != len(received_ids):
        raise ValueError("duplicate_received_record")

    source_by_id = {r["record_id"]: r for r in source}
    received_by_id = {r["record_id"]: r for r in received}
    unexpected = sorted(set(received_by_id) - set(source_by_id))
    conflicting = sorted(
        record_id for record_id in set(received_by_id) & set(source_by_id)
        if received_by_id[record_id] != source_by_id[record_id]
    )
    missing = sorted(set(source_by_id) - set(received_by_id))
    if unexpected or conflicting:
        status = "CONFLICT"
    elif missing:
        status = "INTERRUPTED"
    else:
        status = "COMPLETE"
    return {
        "status": status,
        "expected_record_ids": sorted(source_by_id),
        "received_record_ids": sorted(received_by_id),
        "missing_record_ids": missing,
        "unexpected_record_ids": unexpected,
        "conflicting_record_ids": conflicting,
        "verification_status": "UNVERIFIED",
    }


def resume_transfer(source_records, received_records):
    """Return a merged destination candidate; never mutate either input."""
    report = inspect_transfer(source_records, received_records)
    if report["status"] == "CONFLICT":
        raise ValueError("transfer_conflict_requires_review")
    source = deepcopy(source_records)
    received = deepcopy(received_records)
    received_by_id = {r["record_id"]: r for r in received}
    for record in source:
        if record["record_id"] not in received_by_id:
            received.append(deepcopy(record))
    final_report = inspect_transfer(source, received)
    return {
        "records": received,
        "recovery_status": "RESUMED" if report["status"] == "INTERRUPTED" else "UNCHANGED",
        "transfer_report": final_report,
    }
