"""Command-line intake handoff for work awaiting Living Tree placement."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .intake import create_intake_queue, load_intake_queue, save_intake_queue
from .audited import load_audited, save_audited
from .storage import read_json_strict


def _emit(value: Any) -> None:
    json.dump(value, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="yim001-intake")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="create a persistent intake queue")
    init.add_argument("queue", type=Path)
    init.add_argument("queue_id")
    add = commands.add_parser("add", help="preserve supplied work as awaiting Tree placement")
    add.add_argument("queue", type=Path)
    add.add_argument("content", type=Path)
    add.add_argument("--source-kind", required=True)
    add.add_argument("--source-reference")
    add.add_argument("--language", required=True)
    add.add_argument("--metadata", type=Path)
    add.add_argument("--timestamp")
    listing = commands.add_parser("list", help="list intake packets awaiting placement")
    listing.add_argument("queue", type=Path)
    transfer = commands.add_parser("import", help="import one pending packet into an audited Living Tree store")
    transfer.add_argument("queue", type=Path)
    transfer.add_argument("store", type=Path)
    transfer.add_argument("packet_id")
    transfer.add_argument("--primary-location", required=True)
    transfer.add_argument("--context-location", action="append", dest="context_locations", default=[])
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            queue = create_intake_queue(args.queue, args.queue_id)
            _emit({"status": "created", "queue": str(args.queue), "queue_id": queue.queue_id})
        elif args.command == "add":
            queue = load_intake_queue(args.queue)
            packet = queue.add(
                read_json_strict(args.content),
                source_kind=args.source_kind,
                source_reference=args.source_reference,
                language=args.language,
                metadata=None if args.metadata is None else read_json_strict(args.metadata),
                captured_at=args.timestamp,
            )
            save_intake_queue(queue, args.queue)
            _emit(packet)
        elif args.command == "list":
            queue = load_intake_queue(args.queue)
            _emit({"queue_id": queue.queue_id, "pending_count": len(queue.pending()), "packets": list(queue.pending())})
        else:
            queue = load_intake_queue(args.queue)
            matches = [packet for packet in queue.packets if packet["packet_id"] == args.packet_id]
            if not matches:
                raise ValueError(f"intake packet not found: {args.packet_id}")
            packet = matches[0]
            if packet["placement_status"] == "imported":
                _emit({
                    "status": "already_imported",
                    "packet_id": args.packet_id,
                    "record_id": packet["imported_record_id"],
                    "version_id": packet["imported_version_id"],
                    "primary_location": packet["primary_logical_location"],
                })
                return 0
            audited = load_audited(args.store)
            version = audited.import_intake_packet(
                packet, queue_id=queue.queue_id,
                primary_node_id=args.primary_location,
                contextual_node_ids=args.context_locations,
            )
            # Save the audited capture first. If the queue update is interrupted,
            # retry finds the provenance identity and returns this same version.
            save_audited(audited, args.store)
            queue.mark_imported(
                args.packet_id,
                primary_logical_location=args.primary_location,
                contextual_logical_locations=args.context_locations,
                record_id=version.record_id, version_id=version.version_id,
            )
            save_intake_queue(queue, args.queue)
            _emit({
                "status": "imported",
                "packet_id": args.packet_id,
                "record_id": version.record_id,
                "version_id": version.version_id,
                "primary_location": args.primary_location,
                "contextual_locations": args.context_locations,
            })
        return 0
    except (OSError, ValueError, TypeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
