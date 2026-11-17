#!/usr/bin/env python3
"""Create a deterministic, provenance-bearing GitHub-to-Library handoff bundle.

Input: transfer-manifest.json at repository root.
Output: export/ containing requested source files and transfer-receipt.json.
This exporter never writes to the Living Tree Library; it creates an artifact
for an explicitly authorized downstream import and verification step.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "transfer-manifest.json"
OUT = ROOT / "export"


def fail(message: str) -> None:
    print(f"G2L-001 ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    if not MANIFEST.is_file():
        fail("transfer-manifest.json is missing")
    try:
        config = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"manifest is not valid UTF-8 JSON: {exc}")

    if config.get("protocol") != "G2L-001":
        fail("unsupported or missing protocol identifier")
    files = config.get("files")
    if not isinstance(files, list) or not files:
        fail("files must be a non-empty array")

    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    receipt = {
        "protocol": "G2L-001",
        "source_repository": os.environ.get("GITHUB_REPOSITORY", ""),
        "source_commit": os.environ.get("GITHUB_SHA", ""),
        "files": [],
    }
    seen = set()
    for item in files:
        if not isinstance(item, dict):
            fail("each files entry must be an object")
        rel = item.get("source")
        target = item.get("artifact_path", rel)
        if not isinstance(rel, str) or not isinstance(target, str):
            fail("source and artifact_path must be strings")
        source_rel = PurePosixPath(rel)
        target_rel = PurePosixPath(target)
        if source_rel.is_absolute() or target_rel.is_absolute() or ".." in source_rel.parts or ".." in target_rel.parts:
            fail("absolute paths and parent traversal are forbidden")
        if str(source_rel) in seen:
            fail(f"duplicate source path: {rel}")
        seen.add(str(source_rel))
        src = ROOT.joinpath(*source_rel.parts)
        if not src.is_file() or src.is_symlink():
            fail(f"source is missing, not a regular file, or a symlink: {rel}")
        dst = OUT.joinpath(*target_rel.parts)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        receipt["files"].append({
            "source_path": str(source_rel),
            "artifact_path": str(target_rel),
            "destination_path": item.get("destination_path", ""),
            "size_bytes": dst.stat().st_size,
            "sha256": sha256(dst),
        })

    receipt_path = OUT / "transfer-receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
