import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / "src"

class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "memory.json"
        self.content = self.root / "content.json"
        self.metadata = self.root / "metadata.json"
        self.env = dict(os.environ, PYTHONPATH=str(SOURCE))

    def run_cli(self, *args, expected=0):
        result = subprocess.run([sys.executable, "-m", "yim001", *map(str, args)], cwd=PROJECT, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def write_json(self, path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def output(self, result):
        return json.loads(result.stdout)

    def test_init_create_revise_show_and_verify(self):
        self.run_cli("init", self.store)
        self.write_json(self.content, {"message": "first"})
        self.write_json(self.metadata, {"source": "cli-test"})
        created = self.output(self.run_cli("create", self.store, self.content, "--metadata", self.metadata))
        self.assertEqual(created["content"], {"message": "first"})
        self.assertEqual(created["metadata"], {"source": "cli-test"})
        self.write_json(self.content, {"message": "second"})
        revised = self.output(self.run_cli("revise", self.store, created["record_id"], self.content))
        self.assertEqual(revised["parents"], [created["version_id"]])
        history = self.output(self.run_cli("show", self.store, "--record", created["record_id"]))
        self.assertEqual(len(history["versions"]), 2)
        self.assertEqual(history["tips"], [revised["version_id"]])
        version = self.output(self.run_cli("show", self.store, "--version", created["version_id"]))
        self.assertEqual(version["content"], {"message": "first"})
        verified = self.output(self.run_cli("verify", self.store))
        self.assertEqual(verified, {"conflicts": 0, "records": 1, "status": "verified", "versions": 2})

    def test_init_refuses_to_overwrite_existing_store(self):
        self.run_cli("init", self.store)
        result = self.run_cli("init", self.store, expected=2)
        self.assertIn("refusing to overwrite", result.stderr)

    def test_invalid_json_does_not_change_store(self):
        self.run_cli("init", self.store)
        before = self.store.read_bytes()
        self.content.write_text('{"bad":NaN}', encoding="utf-8")
        result = self.run_cli("create", self.store, self.content, expected=2)
        self.assertIn("non-JSON numeric constant", result.stderr)
        self.assertEqual(self.store.read_bytes(), before)

    def test_unknown_record_does_not_change_store(self):
        self.run_cli("init", self.store)
        self.write_json(self.content, {"message": "x"})
        before = self.store.read_bytes()
        result = self.run_cli("revise", self.store, "missing-record", self.content, expected=2)
        self.assertIn("record not found", result.stderr)
        self.assertEqual(self.store.read_bytes(), before)

    def test_directional_conflict_annotation_persists(self):
        self.run_cli("init", self.store)
        self.write_json(self.content, {"v": 1})
        first = self.output(self.run_cli("create", self.store, self.content))
        self.write_json(self.content, {"v": 2})
        second = self.output(self.run_cli("revise", self.store, first["record_id"], self.content))
        result = self.output(self.run_cli("annotate-conflict", self.store, first["version_id"], second["version_id"]))
        self.assertTrue(result["directional"])
        history = self.output(self.run_cli("show", self.store, "--record", first["record_id"]))
        self.assertEqual(len(history["conflicts"]), 1)
        self.assertEqual(history["conflicts"][0]["source_version_id"], first["version_id"])

    def test_merge_revision_accepts_multiple_explicit_parents(self):
        self.run_cli("init", self.store)
        self.write_json(self.content, {"v": 0})
        first = self.output(self.run_cli("create", self.store, self.content))
        self.write_json(self.content, {"v": 1})
        left = self.output(self.run_cli("revise", self.store, first["record_id"], self.content))
        self.write_json(self.content, {"v": 2})
        right = self.output(self.run_cli("revise", self.store, first["record_id"], self.content, "--parent", first["version_id"]))
        self.write_json(self.content, {"merged": True})
        merged = self.output(self.run_cli("revise", self.store, first["record_id"], self.content, "--parent", left["version_id"], "--parent", right["version_id"]))
        self.assertEqual(set(merged["parents"]), {left["version_id"], right["version_id"]})

    def test_missing_input_file_does_not_change_store(self):
        self.run_cli("init", self.store)
        before = self.store.read_bytes()
        result = self.run_cli("create", self.store, self.root / "missing.json", expected=2)
        self.assertIn("No such file", result.stderr)
        self.assertEqual(self.store.read_bytes(), before)

    def test_event_ledger_cli_round_trip_and_verify(self):
        ledger = self.root / "events.json"
        self.run_cli("event-init", ledger)
        first = self.output(self.run_cli(
            "event-append", ledger, "record.created", "node-a",
            "--record", "r1", "--version", "v1",
            "--timestamp", "2026-11-10T12:00:00Z"
        ))
        self.assertEqual(first["sequence"], 0)
        self.run_cli(
            "event-append", ledger, "record.exported", "node-a",
            "--record", "r1", "--version", "v1",
            "--timestamp", "2026-11-10T12:01:00Z"
        )
        verified = self.output(self.run_cli("event-verify", ledger))
        self.assertEqual(verified["status"], "verified")
        self.assertEqual(verified["events"], 2)
        shown = self.output(self.run_cli("event-show", ledger))
        self.assertEqual(len(shown), 2)
        self.assertEqual(shown[1]["previous_digest"], shown[0]["digest"])

    def test_event_init_refuses_to_overwrite(self):
        ledger = self.root / "events.json"
        self.run_cli("event-init", ledger)
        result = self.run_cli("event-init", ledger, expected=2)
        self.assertIn("refusing to overwrite", result.stderr)

    def test_event_cli_rejects_tampered_ledger(self):
        ledger = self.root / "events.json"
        self.run_cli("event-init", ledger)
        self.run_cli(
            "event-append", ledger, "record.created", "node-a",
            "--timestamp", "2026-11-10T12:00:00Z"
        )
        data = json.loads(ledger.read_text(encoding="utf-8"))
        data["events"][0]["actor_id"] = "tampered"
        ledger.write_text(json.dumps(data), encoding="utf-8")
        result = self.run_cli("event-verify", ledger, expected=2)
        self.assertIn("digest mismatch", result.stderr)


    def _seed_memory_for_mutator(self):
        self.run_cli("init", self.store)
        self.write_json(self.content, {"value": "first"})
        first = self.output(self.run_cli("create", self.store, self.content))
        self.write_json(self.content, {"value": "second"})
        second = self.output(self.run_cli("revise", self.store, first["record_id"], self.content))
        commands = {
            "create": ("create", self.store, self.content),
            "revise": ("revise", self.store, first["record_id"], self.content),
            "annotate-conflict": (
                "annotate-conflict", self.store,
                first["version_id"], second["version_id"],
            ),
        }
        return commands

    def test_memory_mutators_reject_malformed_or_unreadable_store_without_mutation(self):
        for mutation_kind in ("malformed", "unreadable"):
            for command_name in ("create", "revise", "annotate-conflict"):
                with self.subTest(mutation_kind=mutation_kind, command=command_name):
                    self.store = self.root / f"memory-{mutation_kind}-{command_name}.json"
                    commands = self._seed_memory_for_mutator()
                    if mutation_kind == "malformed":
                        snapshot = json.loads(self.store.read_text(encoding="utf-8"))
                        snapshot["records"][0]["version_ids"] = []
                        self.store.write_text(json.dumps(snapshot), encoding="utf-8")
                    else:
                        self.store.write_bytes(b'{"format":')
                    before = self.store.read_bytes()
                    result = self.run_cli(*commands[command_name], expected=2)
                    self.assertTrue(result.stderr.strip(), "rejected write should explain the validation failure")
                    self.assertEqual(self.store.read_bytes(), before)

    def test_event_append_rejects_malformed_or_unreadable_complete_ledger_without_mutation(self):
        for corruption in ("unrelated-malformed-event", "boolean-sequence", "broken-chain", "unreadable"):
            with self.subTest(corruption=corruption):
                ledger = self.root / f"events-{corruption}.json"
                # Seed each corruption case in its own fresh ledger path.
                self.run_cli("event-init", ledger)
                self.run_cli(
                    "event-append", ledger, "record.created", "node-a",
                    "--record", "unrelated-record", "--version", "unrelated-version",
                    "--timestamp", "2026-11-10T12:00:00Z",
                )
                self.run_cli(
                    "event-append", ledger, "record.revised", "node-a",
                    "--record", "unrelated-record", "--version", "unrelated-version-2",
                    "--timestamp", "2026-11-10T12:01:00Z",
                )
                if corruption == "unreadable":
                    ledger.write_bytes(b'{"format":')
                else:
                    snapshot = json.loads(ledger.read_text(encoding="utf-8"))
                    if corruption == "unrelated-malformed-event":
                        snapshot["events"][0]["event_type"] = "unsupported.event"
                    elif corruption == "boolean-sequence":
                        snapshot["events"][0]["sequence"] = True
                    elif corruption == "broken-chain":
                        snapshot["events"][1]["previous_digest"] = "f" * 64
                    ledger.write_text(json.dumps(snapshot), encoding="utf-8")
                before = ledger.read_bytes()
                result = self.run_cli(
                    "event-append", ledger, "record.created", "node-b",
                    "--record", "different-record", "--version", "new-version",
                    "--timestamp", "2026-11-10T12:02:00Z",
                    expected=2,
                )
                self.assertTrue(result.stderr.strip(), "rejected append should explain the validation failure")
                self.assertEqual(ledger.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
