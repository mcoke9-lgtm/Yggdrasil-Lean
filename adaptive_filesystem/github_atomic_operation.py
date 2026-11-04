"""Pure helpers for GitHub's atomic ref-update + operation-marker protocol.

This module builds and classifies a proposed GraphQL updateRefs operation. It
does not call GitHub, create Git objects, verify signatures, or mutate refs.
"""
import hashlib
import json
import re
from collections.abc import Mapping


ZERO_OID = "0" * 40
_OPERATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_OID = re.compile(r"^[0-9a-fA-F]{40}$")


def _require_oid(value, field):
    if not isinstance(value, str) or not _OID.fullmatch(value):
        raise ValueError(f"{field} must be a 40-character hexadecimal Git object ID")
    return value.lower()


def operation_marker_ref(operation_id):
    """Return a deterministic, collision-resistant ref name for one operation."""
    if not isinstance(operation_id, str) or not _OPERATION_ID.fullmatch(operation_id):
        raise ValueError("operation_id has invalid format")
    suffix = hashlib.sha256(operation_id.encode("utf-8")).hexdigest()
    return f"refs/tags/af-operations/{suffix}"


def canonical_marker_record(
    *, operation_id, repository, target_ref, before_oid, after_oid,
    authorization_id, action_fingerprint, scope,
):
    """Build canonical UTF-8 JSON bytes and its digest for an immutable marker commit."""
    marker_ref = operation_marker_ref(operation_id)
    if not isinstance(repository, str) or not repository.strip() or "/" not in repository:
        raise ValueError("repository must be owner/name")
    if not isinstance(target_ref, str) or not target_ref.startswith("refs/heads/"):
        raise ValueError("target_ref must be a fully qualified branch ref")
    if not isinstance(authorization_id, str) or not authorization_id.strip():
        raise ValueError("authorization_id is required")
    if not isinstance(action_fingerprint, str) or not action_fingerprint.strip():
        raise ValueError("action_fingerprint is required")
    if not isinstance(scope, list) or not scope or any(
        not isinstance(item, str) or not item.strip() for item in scope
    ):
        raise ValueError("scope must be a non-empty list of non-empty strings")

    record = {
        "schema": "af-github-ref-operation-v1",
        "operation_id": operation_id,
        "repository": repository,
        "target_ref": target_ref,
        "before_oid": _require_oid(before_oid, "before_oid"),
        "after_oid": _require_oid(after_oid, "after_oid"),
        "authorization_id": authorization_id,
        "action_fingerprint": action_fingerprint,
        "scope": sorted(set(scope)),
        "marker_ref": marker_ref,
    }
    encoded = json.dumps(
        record, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return {
        "record": record,
        "canonical_json": encoded.decode("utf-8"),
        "record_sha256": hashlib.sha256(encoded).hexdigest(),
    }


def build_update_refs_input(
    *, repository_id, client_mutation_id, target_ref, before_oid, after_oid,
    operation_id, marker_commit_oid,
):
    """Build atomic CAS updates for branch ref plus unique marker ref."""
    if not isinstance(repository_id, str) or not repository_id.strip():
        raise ValueError("repository_id is required")
    if not isinstance(client_mutation_id, str) or not client_mutation_id.strip():
        raise ValueError("client_mutation_id is required")
    if not isinstance(target_ref, str) or not target_ref.startswith("refs/heads/"):
        raise ValueError("target_ref must be a fully qualified branch ref")
    before = _require_oid(before_oid, "before_oid")
    after = _require_oid(after_oid, "after_oid")
    marker_oid = _require_oid(marker_commit_oid, "marker_commit_oid")
    marker_ref = operation_marker_ref(operation_id)
    if before == after:
        raise ValueError("before_oid and after_oid must differ")

    return {
        "repositoryId": repository_id,
        # This is a correlation value only; correctness relies on CAS + marker ref.
        "clientMutationId": client_mutation_id,
        "refUpdates": [
            {
                "name": target_ref,
                "beforeOid": before,
                "afterOid": after,
                "force": False,
            },
            {
                "name": marker_ref,
                "beforeOid": ZERO_OID,
                "afterOid": marker_oid,
                "force": False,
            },
        ],
    }


def classify_marker_lookup(*, lookup_status, actual_marker_oid=None, expected_marker_oid=None):
    """Classify marker lookup without inferring success from the branch's current tip."""
    if lookup_status == "FOUND":
        try:
            actual = _require_oid(actual_marker_oid, "actual_marker_oid")
            expected = _require_oid(expected_marker_oid, "expected_marker_oid")
        except ValueError:
            return "MARKER_INVALID"
        return "OPERATION_CONFIRMED" if actual == expected else "MARKER_CONFLICT"
    if lookup_status == "NOT_FOUND":
        return "MARKER_ABSENT_RETRY_EXACT_CAS_ONLY"
    return "RECONCILIATION_REQUIRED"
