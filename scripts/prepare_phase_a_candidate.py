#!/usr/bin/env python3
"""Preflight and explicitly download one pinned Phase A model artifact.

Default behavior is read-only preflight. Network transfer occurs only with --download.
The downloaded weight file is checked against the publisher-reported SHA-256.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import platform
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "schemas/model-artifact-inventory-v0.1.json"
ARTIFACT_ID = "artifact.qwen3_5_0_8b.bf16"
WEIGHT_FILE = "model.safetensors-00001-of-00001.safetensors"
GIB = 1024 ** 3
ALLOW_PATTERNS = [
    WEIGHT_FILE, "config.json", "generation_config.json", "tokenizer.json",
    "tokenizer_config.json", "vocab.json", "merges.txt", "added_tokens.json",
    "special_tokens_map.json", "chat_template.jinja", "preprocessor_config.json",
    "processor_config.json", "video_preprocessor_config.json", "README.md",
]


def available_memory_bytes() -> int | None:
    try:
        for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    try:
        return int(os.sysconf("SC_AVPHYS_PAGES") * os.sysconf("SC_PAGE_SIZE"))
    except (AttributeError, OSError, ValueError):
        return None


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_artifact() -> dict:
    inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
    matches = [item for item in inventory["artifacts"] if item["artifact_id"] == ARTIFACT_ID]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one registered artifact: {ARTIFACT_ID}")
    artifact = matches[0]
    if artifact["version"] != "2fc06364715b967f1860aea9cf38778875588b17":
        raise ValueError("Registered Qwen artifact revision changed; review inventory before proceeding.")
    if artifact["size_bytes"] != 1746942600:
        raise ValueError("Registered Qwen artifact size changed; review inventory before proceeding.")
    return artifact


def preflight(destination: pathlib.Path, artifact: dict) -> dict:
    destination = destination.expanduser().resolve()
    if destination == ROOT or ROOT in destination.parents:
        raise ValueError("Model artifacts must be stored outside the repository working tree.")
    parent = destination if destination.exists() else destination.parent
    while not parent.exists() and parent != parent.parent:
        parent = parent.parent
    free = shutil.disk_usage(parent).free
    required = artifact["size_bytes"] + GIB
    memory = available_memory_bytes()
    return {
        "timestamp_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "artifact_id": artifact["artifact_id"],
        "revision": artifact["version"],
        "expected_size_bytes": artifact["size_bytes"],
        "publisher_sha256": artifact["digest"]["value"],
        "publisher_sha256_status": "not_locally_verified",
        "destination": str(destination),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version.split()[0],
        "available_disk_bytes": free,
        "required_disk_bytes_with_1_gib_headroom": required,
        "disk_preflight_passed": free >= required,
        "available_memory_bytes": memory,
        "memory_measurement_note": "Host-available memory only; does not predict inference-engine peak memory.",
        "network_transfer_performed": False,
        "downloaded": False,
        "local_sha256": None,
        "checksum_verified": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=pathlib.Path, required=True,
                        help="Directory for the pinned model snapshot; no files are written during preflight.")
    parser.add_argument("--download", action="store_true",
                        help="Explicitly authorize downloading the pinned model files to destination.")
    parser.add_argument("--report", type=pathlib.Path,
                        help="Optional path for a local JSON preflight/verification report.")
    args = parser.parse_args()

    artifact = load_artifact()
    report = preflight(args.destination, artifact)
    if not report["disk_preflight_passed"]:
        report["result"] = "blocked_insufficient_disk_space"
    elif not args.download:
        report["result"] = "preflight_only_no_download"
    else:
        try:
            from huggingface_hub import snapshot_download
        except ImportError:
            raise RuntimeError("Install a reviewed huggingface_hub version before using --download.")
        report["network_transfer_performed"] = True
        args.destination.expanduser().resolve().mkdir(parents=True, exist_ok=True)
        snapshot_download(
            repo_id="Qwen/Qwen3.5-0.8B",
            revision=artifact["version"],
            local_dir=str(args.destination.expanduser().resolve()),
            allow_patterns=ALLOW_PATTERNS,
            token=False,
        )
        weight_path = args.destination.expanduser().resolve() / WEIGHT_FILE
        if not weight_path.is_file():
            report["result"] = "download_incomplete_weight_file_missing"
            raise RuntimeError(f"Expected weight file was not present: {weight_path}")
        actual_size = weight_path.stat().st_size
        actual_hash = sha256_file(weight_path)
        report.update({
            "downloaded": True,
            "local_weight_file": str(weight_path),
            "local_size_bytes": actual_size,
            "local_sha256": actual_hash,
            "checksum_verified": actual_size == artifact["size_bytes"] and actual_hash == artifact["digest"]["value"],
            "publisher_sha256_status": "locally_verified" if actual_size == artifact["size_bytes"] and actual_hash == artifact["digest"]["value"] else "mismatch",
        })
        report["result"] = "checksum_verified" if report["checksum_verified"] else "checksum_mismatch"
    if args.report:
        args.report.expanduser().resolve().parent.mkdir(parents=True, exist_ok=True)
        args.report.expanduser().resolve().write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["disk_preflight_passed"] and report["result"] in ("preflight_only_no_download", "checksum_verified") else 2


if __name__ == "__main__":
    raise SystemExit(main())
