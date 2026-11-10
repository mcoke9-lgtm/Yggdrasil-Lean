"""Read-only receiver-side verification for transferred YIM-001 recovery files.

This command validates every supplied checkpoint record and backup. It never
writes, imports, or restores data. Supply the complete checkpoint chain and
all backups referenced by that chain, plus an independently retained source
and latest-sequence anchor.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from yim001 import verify_checkpoint_chain
from yim001.storage import read_json_strict


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify transferred YIM-001 recovery evidence without modifying it."
    )
    parser.add_argument("--checkpoints", required=True, type=Path,
                        help="JSON file containing the complete checkpoint-record list")
    parser.add_argument("--backup", required=True, action="append", type=Path,
                        help="backup JSON file; repeat for every checkpoint backup")
    parser.add_argument("--expected-source", required=True,
                        help="source instance ID obtained from an independent trusted record")
    parser.add_argument("--expected-latest-sequence", required=True, type=int,
                        help="latest sequence obtained from an independently retained anchor")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        records = read_json_strict(args.checkpoints)
        if not isinstance(records, list):
            raise ValueError("checkpoint file must contain a JSON list")
        backups = [read_json_strict(path) for path in args.backup]
        report = verify_checkpoint_chain(
            records,
            backups,
            expected_latest_sequence=args.expected_latest_sequence,
            expected_source_instance_id=args.expected_source,
        )
        report["receiver_check"] = "read_only"
        report["files_modified"] = False
        report["backup_files_supplied"] = len(backups)
        json.dump(report, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0 if report["status"] == "VERIFIED" else 2
    except (OSError, ValueError, TypeError) as exc:
        print(f"yim001-recovery-receive: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
