import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / "src"


class AuditedCommandLineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "audited.json"
        self.content = self.root / "content.json"
        self.env = dict(os.environ, PYTHONPATH=str(SOURCE))

    def run_cli(self, *args, expected=0):
        result = subprocess.run(
            [sys.executable, "-m", "yim001", *map(str, args)],
            cwd=PROJECT, env=self.env, capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, expected, result.stderr)
        return result

    def write_content(self, value):
        self.content.write_text(json.dumps(value), encoding="utf-8")

    @staticmethod
    def output(result):
        return json.loads(result.stdout)

    def test_audited_cli_full_workflow(self):
        created_store = self.output(self.run_cli("audited-init", self.store, "cli-node"))
        self.assertTrue(created_store["audited"])
        self.write_content({"value": 1})
        first = self.output(self.run_cli(
            "audited-create", self.store, self.content,
            "--timestamp", "2026-11-10T12:00:00Z",
        ))
        self.write_content({"value": 2})
        second = self.output(self.run_cli(
            "audited-revise", self.store, first["record_id"], self.content,
            "--timestamp", "2026-11-10T12:01:00Z",
        ))
        self.assertEqual(second["parents"], [first["version_id"]])
        conflict = self.output(self.run_cli(
            "audited-annotate-conflict", self.store,
            first["version_id"], second["version_id"],
            "--timestamp", "2026-11-10T12:02:00Z",
        ))
        self.assertTrue(conflict["directional"])

        history = self.output(self.run_cli(
            "audited-show", self.store, "--record", first["record_id"]
        ))
        self.assertEqual(len(history["versions"]), 2)
        self.assertEqual(len(history["conflicts"]), 1)

        events = self.output(self.run_cli("audited-show", self.store, "--events"))
        self.assertEqual(
            [event["event_type"] for event in events],
            ["record.created", "record.revised", "conflict.annotated"],
        )
        self.assertEqual([event["sequence"] for event in events], [0, 1, 2])
        self.assertEqual(events[2]["previous_digest"], events[1]["digest"])
        self.assertTrue(all(event["actor_id"] == "cli-node" for event in events))

        verified = self.output(self.run_cli("audited-verify", self.store))
        self.assertEqual(verified["status"], "verified")
        self.assertEqual(verified["events"], 3)
        self.assertEqual(verified["records"], 1)
        self.assertEqual(verified["versions"], 2)
        self.assertEqual(verified["conflicts"], 1)

    def test_failed_audited_event_does_not_change_combined_file(self):
        self.run_cli("audited-init", self.store, "cli-node")
        self.write_content({"value": 1})
        first = self.output(self.run_cli("audited-create", self.store, self.content))
        before = self.store.read_bytes()
        self.write_content({"value": 2})
        result = self.run_cli(
            "audited-revise", self.store, first["record_id"], self.content,
            "--timestamp", "not-a-timestamp", expected=2,
        )
        self.assertIn("timestamp", result.stderr)
        self.assertEqual(self.store.read_bytes(), before)
        verified = self.output(self.run_cli("audited-verify", self.store))
        self.assertEqual(verified["versions"], 1)
        self.assertEqual(verified["events"], 1)

    def test_audited_init_refuses_to_overwrite(self):
        self.run_cli("audited-init", self.store, "cli-node")
        before = self.store.read_bytes()
        result = self.run_cli("audited-init", self.store, "other-node", expected=2)
        self.assertIn("refusing to overwrite", result.stderr)
        self.assertEqual(self.store.read_bytes(), before)

    def test_audited_verify_rejects_tampered_event_chain(self):
        self.run_cli("audited-init", self.store, "cli-node")
        self.write_content({"value": 1})
        self.run_cli("audited-create", self.store, self.content)
        data = json.loads(self.store.read_text(encoding="utf-8"))
        data["provenance"]["events"][0]["actor_id"] = "forged"
        self.store.write_text(json.dumps(data), encoding="utf-8")
        result = self.run_cli("audited-verify", self.store, expected=2)
        self.assertIn("digest mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
