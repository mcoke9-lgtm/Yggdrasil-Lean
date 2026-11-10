"""Portable command-line interface for a local YIM-001 memory store."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
from typing import Any
from . import MemoryStore, load_store, read_json_strict, save_store

def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="yim001", description="Create, inspect, revise, and verify a local YIM-001 memory store.")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="create a new empty memory store")
    init.add_argument("store", type=Path)
    create = commands.add_parser("create", help="create a record from a JSON file")
    create.add_argument("store", type=Path)
    create.add_argument("content", type=Path)
    create.add_argument("--metadata", type=Path)
    revise = commands.add_parser("revise", help="append a version to an existing record")
    revise.add_argument("store", type=Path)
    revise.add_argument("record_id")
    revise.add_argument("content", type=Path)
    revise.add_argument("--parent", action="append", dest="parents")
    revise.add_argument("--metadata", type=Path)
    show = commands.add_parser("show", help="display a record history or one version")
    show.add_argument("store", type=Path)
    target = show.add_mutually_exclusive_group(required=True)
    target.add_argument("--record", dest="record_id")
    target.add_argument("--version", dest="version_id")
    conflict = commands.add_parser("annotate-conflict", help="add a directional conflict annotation")
    conflict.add_argument("store", type=Path)
    conflict.add_argument("source_version_id")
    conflict.add_argument("target_version_id")
    verify = commands.add_parser("verify", help="load and validate a memory snapshot")
    verify.add_argument("store", type=Path)
    return parser

def _read_optional(path: Path | None) -> Any:
    return None if path is None else read_json_strict(path)

def _version_data(version: Any) -> dict[str, Any]:
    return {"version_id": version.version_id, "record_id": version.record_id, "parents": list(version.parents), "content": version.content, "metadata": version.metadata}

def _emit(value: Any) -> None:
    json.dump(value, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")

def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "init":
            if args.store.exists():
                raise FileExistsError(f"refusing to overwrite existing store: {args.store}")
            args.store.parent.mkdir(parents=True, exist_ok=True)
            save_store(MemoryStore(), args.store)
            _emit({"status": "created", "store": str(args.store)})
            return 0
        if args.command == "verify":
            store = load_store(args.store)
            snapshot = store.to_snapshot()
            _emit({"status": "verified", "records": len(snapshot["records"]), "versions": len(snapshot["versions"]), "conflicts": len(snapshot["conflicts"])})
            return 0
        store = load_store(args.store)
        if args.command == "create":
            version = store.create_record(read_json_strict(args.content), _read_optional(args.metadata))
            save_store(store, args.store)
            _emit(_version_data(version))
        elif args.command == "revise":
            version = store.revise(args.record_id, read_json_strict(args.content), parent_version_ids=args.parents, metadata=_read_optional(args.metadata))
            save_store(store, args.store)
            _emit(_version_data(version))
        elif args.command == "show":
            if args.version_id:
                _emit(_version_data(store.get_version(args.version_id)))
            else:
                _emit({"record_id": args.record_id, "versions": [_version_data(v) for v in store.versions(args.record_id)], "tips": list(store.tips(args.record_id)), "conflicts": [{"source_version_id": c.source_version_id, "target_version_id": c.target_version_id} for c in store.conflicts(args.record_id)]})
        elif args.command == "annotate-conflict":
            conflict = store.annotate_conflict(args.source_version_id, args.target_version_id)
            save_store(store, args.store)
            _emit({"source_version_id": conflict.source_version_id, "target_version_id": conflict.target_version_id, "directional": True})
        return 0
    except (OSError, ValueError, TypeError) as exc:
        print(f"yim001: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
