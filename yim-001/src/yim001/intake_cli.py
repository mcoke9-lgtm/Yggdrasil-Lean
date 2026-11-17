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


def _verify_imported_packet(queue, packet, audited, packet_id: str) -> dict[str, Any]:
    version = audited.get_version(packet["imported_version_id"])
    if version.record_id != packet["imported_record_id"]:
        raise ValueError("queue import identity does not match the audited version")
    capture = version.metadata.get("capture") if isinstance(version.metadata, dict) else None
    identity = {"queue_id": queue.queue_id, "packet_id": packet["packet_id"]}
    if (
        version.content != packet["content"]
        or not isinstance(capture, dict)
        or capture.get("intake_identity") != identity
        or capture.get("source_kind") != packet["source_kind"]
        or capture.get("source_reference") != packet["source_reference"]
        or capture.get("language") != packet["language"]
        or capture.get("source_content_preserved_as_submitted") is not True
        or capture.get("primary_logical_location") != packet["primary_logical_location"]
        or capture.get("contextual_logical_locations") != packet["contextual_logical_locations"]
        or version.metadata.get("user_metadata", {}) != packet["metadata"]
    ):
        raise ValueError("queue import marker does not match audited content or Tree placement")
    placements = audited.placements_for(version.record_id)
    actual_primary = [item.node_id for item in placements if item.role == "primary"]
    actual_contextual = [item.node_id for item in placements if item.role == "contextual"]
    if (
        actual_primary != [packet["primary_logical_location"]]
        or actual_contextual != packet["contextual_logical_locations"]
    ):
        raise ValueError("audited Tree placements do not match the imported queue packet")
    return {
        "status": "already_imported_verified",
        "packet_id": packet_id,
        "record_id": version.record_id,
        "version_id": version.version_id,
        "primary_location": packet["primary_logical_location"],
    }


def _import_packet(queue, packet, audited, store_path: Path, queue_path: Path,
                   primary_location: str, context_locations: list[str]) -> dict[str, Any]:
    if packet["placement_status"] == "imported":
        return _verify_imported_packet(queue, packet, audited, packet["packet_id"])
    version = audited.import_intake_packet(
        packet, queue_id=queue.queue_id,
        primary_node_id=primary_location,
        contextual_node_ids=context_locations,
    )
    # Persist the audited capture first. A retry can recognize it if the queue
    # update is interrupted; each batch item is checkpointed independently.
    save_audited(audited, store_path)
    queue.mark_imported(
        packet["packet_id"],
        primary_logical_location=primary_location,
        contextual_logical_locations=context_locations,
        record_id=version.record_id, version_id=version.version_id,
    )
    save_intake_queue(queue, queue_path)
    return {
        "status": "imported",
        "packet_id": packet["packet_id"],
        "record_id": version.record_id,
        "version_id": version.version_id,
        "primary_location": primary_location,
        "contextual_locations": context_locations,
    }


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
    batch = commands.add_parser(
        "import-pending",
        help="import all pending packets to one explicitly selected Tree location, checkpointing each",
    )
    batch.add_argument("queue", type=Path)
    batch.add_argument("store", type=Path)
    for command in (transfer, batch):
        command.add_argument("--primary-location", required=True)
        command.add_argument("--context-location", action="append", dest="context_locations", default=[])
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
        elif args.command == "import":
            queue = load_intake_queue(args.queue)
            matches = [packet for packet in queue.packets if packet["packet_id"] == args.packet_id]
            if not matches:
                raise ValueError(f"intake packet not found: {args.packet_id}")
            audited = load_audited(args.store)
            result = _import_packet(
                queue, matches[0], audited, args.store, args.queue,
                args.primary_location, args.context_locations,
            )
            _emit(result)
        else:
            queue = load_intake_queue(args.queue)
            pending = list(queue.pending())
            if not pending:
                _emit({"status": "no_pending_packets", "queue_id": queue.queue_id, "imported_count": 0, "packets": []})
                return 0
            audited = load_audited(args.store)
            results = []
            for packet in pending:
                results.append(_import_packet(
                    queue, packet, audited, args.store, args.queue,
                    args.primary_location, args.context_locations,
                ))
            _emit({
                "status": "imported_pending_packets",
                "queue_id": queue.queue_id,
                "imported_count": len(results),
                "primary_location": args.primary_location,
                "contextual_locations": args.context_locations,
                "packets": results,
            })
        return 0
    except (OSError, ValueError, TypeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
