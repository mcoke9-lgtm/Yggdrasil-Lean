"""Build and verify a deterministic portable YIM-001 source package."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROJECT_DIR = PROJECT_ROOT / "yim-001"
MANIFEST_PATH = "yim-001/SHA256SUMS.json"


def _files() -> list[Path]:
    excluded_parts = {"__pycache__", ".pytest_cache", ".mypy_cache", "dist"}
    return sorted(
        path for path in PROJECT_DIR.rglob("*")
        if path.is_file()
        and not any(part in excluded_parts for part in path.parts)
        and path.suffix not in {".pyc", ".tmp", ".zip"}
        and path.relative_to(PROJECT_ROOT).as_posix() != MANIFEST_PATH
    )


def build_package(output: Path) -> str:
    files = _files()
    entries = []
    for path in files:
        payload = path.read_bytes()
        entries.append({
            "path": path.relative_to(PROJECT_ROOT).as_posix(),
            "size": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        })
    manifest = json.dumps(
        {"algorithm": "sha256", "files": entries},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            _write_deterministic(archive, path.relative_to(PROJECT_ROOT).as_posix(), path.read_bytes())
        _write_deterministic(archive, MANIFEST_PATH, manifest)
    return hashlib.sha256(output.read_bytes()).hexdigest()


def _write_deterministic(archive: zipfile.ZipFile, name: str, payload: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, payload)


def verify_package(package: Path) -> dict:
    with zipfile.ZipFile(package, "r") as archive:
        bad_member = archive.testzip()
        if bad_member is not None:
            raise ValueError(f"corrupt ZIP member: {bad_member}")
        names = set(archive.namelist())
        if MANIFEST_PATH not in names:
            raise ValueError("package manifest is missing")
        manifest = json.loads(archive.read(MANIFEST_PATH))
        if manifest.get("algorithm") != "sha256" or not isinstance(manifest.get("files"), list):
            raise ValueError("invalid SHA-256 manifest")
        expected_names = {entry["path"] for entry in manifest["files"]}
        if names != expected_names | {MANIFEST_PATH}:
            raise ValueError("package members do not match the manifest")
        for entry in manifest["files"]:
            payload = archive.read(entry["path"])
            if len(payload) != entry["size"]:
                raise ValueError(f"size mismatch: {entry['path']}")
            if hashlib.sha256(payload).hexdigest() != entry["sha256"]:
                raise ValueError(f"SHA-256 mismatch: {entry['path']}")
    return {
        "package": str(package),
        "sha256": hashlib.sha256(package.read_bytes()).hexdigest(),
        "files_verified": len(manifest["files"]),
        "manifest": MANIFEST_PATH,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--output", type=Path)
    group.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.output:
        digest = build_package(args.output)
        result = verify_package(args.output)
        result["sha256"] = digest
    else:
        result = verify_package(args.verify)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
