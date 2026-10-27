import io
import json
import shutil
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from adaptive_filesystem_runtime.cli import main


class CliTests(unittest.TestCase):
    def run_cli(self, command, payload):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("sys.stdin", io.StringIO(json.dumps(payload))), redirect_stdout(stdout), redirect_stderr(stderr):
            code = main([command])
        return code, stdout.getvalue(), stderr.getvalue()

    def test_audit_command_emits_json_and_coverage(self):
        code, output, error = self.run_cli("audit", {
            "coverage": "partial",
            "records": [{"record_id": "a", "branch_id": "main", "sequence": 1}],
        })
        report = json.loads(output)
        self.assertEqual(code, 0)
        self.assertEqual(error, "")
        self.assertEqual(report["coverage_state"], "declared_partial")
        self.assertEqual(report["schema_version"], "af.snapshot-audit.v1")

    def test_resolve_command_preserves_requested_relationships(self):
        code, output, _ = self.run_cli("resolve", {
            "record_id": "b",
            "records": [
                {"record_id": "a", "branch_id": "main", "sequence": 1},
                {"record_id": "b", "branch_id": "main", "sequence": 2, "parent_record_id": "a"},
            ],
        })
        report = json.loads(output)
        self.assertEqual(code, 0)
        self.assertEqual(report["parent_record_id"], "a")

    def test_evaluate_command_reports_no_execution_or_persistence(self):
        code, output, _ = self.run_cli("evaluate", {"request": {
            "request_id": "r1", "active_branch": "branch-a", "checkpoint_id": "AF-PILOT-006",
            "current_goal": "recover", "blocked_operation": "live read",
            "observed_failure": "no credentials", "blocked": True,
            "candidates": [],
        }})
        report = json.loads(output)
        self.assertEqual(code, 0)
        self.assertEqual(report["outcome"], "UNRESOLVED")
        self.assertEqual(report["execution_status"], "not_attempted")
        self.assertEqual(report["persistence_status"], "not_persisted")

    def test_invalid_input_is_json_error_not_traceback(self):
        code, output, error = self.run_cli("resolve", {"records": [], "record_id": ""})
        self.assertEqual(code, 2)
        self.assertEqual(output, "")
        self.assertEqual(json.loads(error)["error"], "ValueError")
        self.assertNotIn("Traceback", error)

    def test_installed_module_cli_runs_as_a_process(self):
        process = subprocess.run(
            [sys.executable, "-m", "adaptive_filesystem_runtime", "audit"],
            input=json.dumps({"records": [
                {"record_id": "a", "branch_id": "main", "sequence": 1}
            ]}),
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(json.loads(process.stdout)["schema_version"], "af.snapshot-audit.v1")

    def test_installed_console_entrypoint_exists(self):
        executable = shutil.which("af-runtime")
        self.assertIsNotNone(executable, "package install should register af-runtime")
        process = subprocess.run(
            [executable, "resolve"],
            input=json.dumps({"record_id": "a", "records": [
                {"record_id": "a", "branch_id": "main", "sequence": 1}
            ]}),
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(json.loads(process.stdout)["record_id"], "a")

    def test_missing_reference_returns_nonzero_exit(self):
        code, output, error = self.run_cli("resolve", {
            "record_id": "b",
            "records": [{"record_id": "b", "branch_id": "main", "sequence": 1,
                         "parent_record_id": "missing"}],
        })
        self.assertEqual(code, 2)
        self.assertEqual(output, "")
        error_report = json.loads(error)
        self.assertEqual(error_report["error"], "ResolutionError")
        self.assertEqual(error_report["code"], "MISSING_PARENT")


if __name__ == "__main__":
    unittest.main()
