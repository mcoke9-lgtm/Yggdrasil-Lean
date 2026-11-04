"""Deterministic action fingerprinting for the AF prototype.

This is a deliberately constrained canonical JSON profile, not an RFC 8785
implementation. All identity/branch/checkpoint fields are non-empty strings;
scope is a non-empty list of unique non-empty strings and is treated as a set.
"""
import hashlib
import json


FINGERPRINT_VERSION = "af-action-v1"


def canonical_action_payload(request):
    """Return the normalized, versioned action description or raise ValueError."""
    fields = (
        "actor",
        "source_branch",
        "source_checkpoint",
        "target_branch",
        "scope",
    )
    if not isinstance(request, dict):
        raise ValueError("request must be a dictionary")

    payload = {"fingerprint_version": FINGERPRINT_VERSION}
    for field in fields[:-1]:
        value = request.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{field} must be a non-empty string")
        payload[field] = value

    scope = request.get("scope")
    if (
        not isinstance(scope, list)
        or not scope
        or any(not isinstance(item, str) or not item.strip() for item in scope)
        or len(set(scope)) != len(scope)
    ):
        raise ValueError("scope must be a non-empty list of unique strings")
    payload["scope"] = sorted(scope)
    return payload


def action_fingerprint(request):
    """Hash the canonical action payload using SHA-256."""
    payload = canonical_action_payload(request)
    encoded = json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()
