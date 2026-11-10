"""Portable command-line interface for a local YIM-001 memory store."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
from typing import Any
from . import (
    AuditedMemoryStore, EventLedger, MemoryStore, load_audited, load_store,
    read_json_strict, save_audited, save_store, write_json_atomic,
)


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

    audited_init = commands.add_parser("audited-init", help="create a combined audited memory store")
    audited_init.add_argument("store", type=Path)
    audited_init.add_argument("actor_id", help="caller-supplied attribution identifier")
    audited_create = commands.add_parser("audited-create", help="create a record and automatically append provenance")
    audited_create.add_argument("store", type=Path)
    audited_create.add_argument("content", type=Path)
    audited_create.add_argument("--metadata", type=Path)
    audited_create.add_argument("--timestamp")
    audited_revise = commands.add_parser("audited-revise", help="revise a record and automatically append provenance")
    audited_revise.add_argument("store", type=Path)
    audited_revise.add_argument("record_id")
    audited_revise.add_argument("content", type=Path)
    audited_revise.add_argument("--parent", action="append", dest="parents")
    audited_revise.add_argument("--metadata", type=Path)
    audited_revise.add_argument("--timestamp")
    audited_conflict = commands.add_parser("audited-annotate-conflict", help="annotate a conflict and automatically append provenance")
    audited_conflict.add_argument("store", type=Path)
    audited_conflict.add_argument("source_version_id")
    audited_conflict.add_argument("target_version_id")
    audited_conflict.add_argument("--timestamp")
    audited_show = commands.add_parser("audited-show", help="display an audited record history, version, or event ledger")
    audited_show.add_argument("store", type=Path)
    audited_target = audited_show.add_mutually_exclusive_group(required=True)
    audited_target.add_argument("--record", dest="record_id")
    audited_target.add_argument("--version", dest="version_id")
    audited_target.add_argument("--events", action="store_true")
    audited_verify = commands.add_parser("audited-verify", help="verify both memory snapshot and provenance chain")
    audited_verify.add_argument("store", type=Path)

    event_init = commands.add_parser("event-init", help="create a separate provenance ledger")
    event_init.add_argument("ledger", type=Path)
    event_append = commands.add_parser("event-append", help="append a provenance event")
    event_append.add_argument("ledger", type=Path)
    event_append.add_argument("event_type")
    event_append.add_argument("actor_id")
    event_append.add_argument("--record")
    event_append.add_argument("--version")
    event_append.add_argument("--details", type=Path)
    event_append.add_argument("--timestamp")
    event_verify = commands.add_parser("event-verify", help="verify a provenance ledger and hash chain")
    event_verify.add_argument("ledger", type=Path)
    event_show = commands.add_parser("event-show", help="display provenance events")
    event_show.add_argument("ledger", type=Path)
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
        if args.command == "audited-init":
            if args.store.exists():
                raise FileExistsError(f"refusing to overwrite existing store: {args.store}")
            args.store.parent.mkdir(parents=True, exist_ok=True)
            save_audited(AuditedMemoryStore(args.actor_id), args.store)
            _emit({"status": "created", "store": str(args.store), "actor_id": args.actor_id, "audited": True})
            return 0
        if args.command in {"audited-create", "audited-revise", "audited-annotate-conflict", "audited-show", "audited-verify"}:
            audited = load_audited(args.store)
            if args.command == "audited-create":
                version = audited.create_record(
                    read_json_strict(args.content), _read_optional(args.metadata), timestamp=args.timestamp
                )
                save_audited(audited, args.store)
                _emit(_version_data(version))
            elif args.command == "audited-revise":
                version = audited.revise(
                    args.record_id, read_json_strict(args.content),
                    parent_version_ids=args.parents, metadata=_read_optional(args.metadata),
                    timestamp=args.timestamp,
                )
                save_audited(audited, args.store)
                _emit(_version_data(version))
            elif args.command == "audited-annotate-conflict":
                conflict = audited.annotate_conflict(
                    args.source_version_id, args.target_version_id, timestamp=args.timestamp
                )
                save_audited(audited, args.store)
                _emit({"source_version_id": conflict.source_version_id, "target_version_id": conflict.target_version_id, "directional": True})
            elif args.command == "audited-verify":
                snapshot = audited.to_snapshot()
                _emit({
                    "status": "verified", "audited": True, "actor_id": audited.actor_id,
                    "records": len(snapshot["memory"]["records"]),
                    "versions": len(snapshot["memory"]["versions"]),
                    "conflicts": len(snapshot["memory"]["conflicts"]),
                    "events": len(audited.events),
                    "last_event_digest": audited.events[-1].digest if audited.events else None,
                })
            elif args.events:
                _emit([event.to_dict() for event in audited.events])
            elif args.version_id:
                _emit(_version_data(audited.get_version(args.version_id)))
            else:
                _emit({
                    "record_id": args.record_id,
                    "versions": [_version_data(v) for v in audited.versions(args.record_id)],
                    "tips": list(audited.tips(args.record_id)),
                    "conflicts": [
                        {"source_version_id": c.source_version_id, "target_version_id": c.target_version_id}
                        for c in audited.conflicts(args.record_id)
                    ],
                })
            return 0
        if args.command == "event-init":
            if args.ledger.exists():
                raise FileExistsError(f"refusing to overwrite existing ledger: {args.ledger}")
            args.ledger.parent.mkdir(parents=True, exist_ok=True)
            write_json_atomic(args.ledger, EventLedger().to_snapshot())
            _emit({"status": "created", "ledger": str(args.ledger)})
            return 0
        if args.command in {"event-append", "event-verify", "event-show"}:
            ledger = EventLedger.from_snapshot(read_json_strict(args.ledger))
            if args.command == "event-append":
                event = ledger.append(
                    args.event_type, args.actor_id,
                    record_id=args.record, version_id=args.version,
                    details=_read_optional(args.details),
                    timestamp=args.timestamp,
                )
                write_json_atomic(args.ledger, ledger.to_snapshot())
                _emit(event.to_dict())
            elif args.command == "event-verify":
                _emit({"status": "verified", "events": len(ledger.events), "last_digest": ledger.events[-1].digest if ledger.events else None})
            else:
                _emit([event.to_dict() for event in ledger.events])
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
