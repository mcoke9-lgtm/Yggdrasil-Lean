"""Portable, offline-verifiable Yggdrasil evidence bundle (synthetic core).

The bundle is a ZIP containing records.jsonl, relationships.jsonl and
manifest.json. Hashes establish byte consistency with this manifest only;
they do not establish source authenticity or truth.
"""
import hashlib
import io
import json
import zipfile

DATA_FILES = ("records.jsonl", "relationships.jsonl")
MAX_BUNDLE_BYTES = 5 * 1024 * 1024
MAX_MEMBER_BYTES = 2 * 1024 * 1024


def _canonical_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def _jsonl(items):
    return b"".join(_canonical_json(item) + b"\n" for item in items)


def build_bundle(records, relationships):
    """Build a deterministic ZIP in memory; never mutates caller inputs."""
    records_bytes = _jsonl(records)
    relationships_bytes = _jsonl(relationships)
    manifest = {
        "format": "yggdrasil-portable-bundle",
        "version": "0.1.0",
        "hash_algorithm": "sha256",
        "files": {
            "records.jsonl": {
                "sha256": hashlib.sha256(records_bytes).hexdigest(),
                "count": len(records),
            },
            "relationships.jsonl": {
                "sha256": hashlib.sha256(relationships_bytes).hexdigest(),
                "count": len(relationships),
            },
        },
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in (
            ("records.jsonl", records_bytes),
            ("relationships.jsonl", relationships_bytes),
            ("manifest.json", _canonical_json(manifest) + b"\n"),
        ):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.external_attr = 0o100600 << 16
            archive.writestr(info, payload)
    return output.getvalue()


def verify_bundle(bundle_bytes):
    """Verify exact members, hashes, JSONL identities and relationship endpoints."""
    if not isinstance(bundle_bytes, (bytes, bytearray)):
        return {"status": "INVALID", "reasons": ["bundle_not_bytes"]}
    if len(bundle_bytes) > MAX_BUNDLE_BYTES:
        return {"status": "INVALID", "reasons": ["bundle_too_large"]}
    try:
        with zipfile.ZipFile(io.BytesIO(bundle_bytes), "r") as archive:
            infos = archive.infolist()
            names = [item.filename for item in infos]
            expected = set(DATA_FILES) | {"manifest.json"}
            if len(names) != len(set(names)):
                return {"status": "INVALID", "reasons": ["duplicate_member"]}
            if set(names) != expected:
                return {"status": "INVALID", "reasons": ["member_set_invalid"]}
            for item in infos:
                if item.is_dir() or item.file_size > MAX_MEMBER_BYTES:
                    return {"status": "INVALID", "reasons": ["member_type_or_size_invalid"]}
                mode = item.external_attr >> 16
                if mode and (mode & 0o170000) == 0o120000:
                    return {"status": "INVALID", "reasons": ["symlink_member_rejected"]}
            payloads = {name: archive.read(name) for name in names}
    except (zipfile.BadZipFile, OSError, RuntimeError, ValueError):
        return {"status": "INVALID", "reasons": ["zip_unreadable"]}

    try:
        manifest = json.loads(payloads["manifest.json"].decode("utf-8"))
        if manifest.get("format") != "yggdrasil-portable-bundle":
            raise ValueError("manifest_format_invalid")
        if manifest.get("version") != "0.1.0" or manifest.get("hash_algorithm") != "sha256":
            raise ValueError("manifest_version_or_hash_invalid")
        files = manifest.get("files")
        if not isinstance(files, dict) or set(files) != set(DATA_FILES):
            raise ValueError("manifest_file_set_invalid")
        parsed = {}
        for name in DATA_FILES:
            entry = files[name]
            digest = hashlib.sha256(payloads[name]).hexdigest()
            if entry.get("sha256") != digest:
                raise ValueError("hash_mismatch:" + name)
            lines = payloads[name].splitlines()
            parsed[name] = [json.loads(line.decode("utf-8")) for line in lines]
            if entry.get("count") != len(parsed[name]):
                raise ValueError("count_mismatch:" + name)
        records = parsed["records.jsonl"]
        relationships = parsed["relationships.jsonl"]
        record_ids = [r.get("record_id") for r in records if isinstance(r, dict)]
        relationship_ids = [r.get("relationship_id") for r in relationships if isinstance(r, dict)]
        if len(record_ids) != len(records) or any(not x for x in record_ids):
            raise ValueError("record_identity_invalid")
        if len(set(record_ids)) != len(record_ids):
            raise ValueError("duplicate_record_id")
        if len(relationship_ids) != len(relationships) or any(not x for x in relationship_ids):
            raise ValueError("relationship_identity_invalid")
        if len(set(relationship_ids)) != len(relationship_ids):
            raise ValueError("duplicate_relationship_id")
        known = set(record_ids)
        for rel in relationships:
            if rel.get("source_id") not in known or rel.get("target_id") not in known:
                raise ValueError("relationship_endpoint_missing")
            if not rel.get("provenance") or not rel.get("relationship_type") or not rel.get("status"):
                raise ValueError("relationship_semantics_or_provenance_missing")
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, AttributeError, TypeError, ValueError) as error:
        return {"status": "INVALID", "reasons": [str(error)]}

    return {
        "status": "VALID",
        "record_count": len(records),
        "relationship_count": len(relationships),
        "verification_scope": "bundle_integrity_and_internal_references_only",
        "source_authenticity": "NOT_ESTABLISHED",
        "source_truth": "NOT_ESTABLISHED",
    }
