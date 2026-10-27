"""Read-only normalization boundary for Airtable Records API-shaped data.

This module performs no network requests and no writes. A caller must retrieve
records through an authorized connector and pass their field dictionaries here.
It maps actual pilot field names to the resolver model and never repairs links.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from .resolver import Record, ResolutionError


def _optional_text(fields: Mapping[str, Any], name: str, record_id: str) -> str | None:
    value = fields.get(name)
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise ResolutionError("MALFORMED_FIELD", f"{record_id}.{name} must be text or blank")
    if value != value.strip():
        raise ResolutionError("MALFORMED_REFERENCE", f"{record_id}.{name} has surrounding whitespace")
    return value


def records_from_airtable(rows: Sequence[Mapping[str, Any]]) -> list[Record]:
    """Normalize Airtable rows with fields dictionaries into resolver Records.

    Expected field names are the actual pilot schema: Record ID, Branch ID,
    Sequence, Parent Record ID, Source Branch ID, Source Anchor ID, Revision Of ID.
    Airtable's internal record ID is not substituted for the application Record ID.
    """
    normalized: list[Record] = []
    for position, row in enumerate(rows):
        if not isinstance(row, Mapping):
            raise ResolutionError("MALFORMED_AIRTABLE_ROW", f"row {position} is not an object")
        fields = row.get("fields")
        if not isinstance(fields, Mapping):
            raise ResolutionError("MALFORMED_AIRTABLE_ROW", f"row {position} has no fields object")
        record_id = fields.get("Record ID")
        branch_id = fields.get("Branch ID")
        sequence = fields.get("Sequence")
        if not isinstance(record_id, str) or not record_id or record_id != record_id.strip():
            raise ResolutionError("MALFORMED_FIELD", f"row {position} has invalid Record ID")
        if not isinstance(branch_id, str) or not branch_id or branch_id != branch_id.strip():
            raise ResolutionError("MALFORMED_FIELD", f"{record_id}.Branch ID is required text")
        if not isinstance(sequence, (int, float)) or isinstance(sequence, bool) or int(sequence) != sequence:
            raise ResolutionError("MALFORMED_FIELD", f"{record_id}.Sequence must be an integer")
        normalized.append(
            Record(
                record_id=record_id,
                branch_id=branch_id,
                sequence=int(sequence),
                parent_record_id=_optional_text(fields, "Parent Record ID", record_id),
                source_branch_id=_optional_text(fields, "Source Branch ID", record_id),
                source_anchor_id=_optional_text(fields, "Source Anchor ID", record_id),
                revision_of_id=_optional_text(fields, "Revision Of ID", record_id),
            )
        )
    return normalized
