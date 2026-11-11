"""THO-015 Probe 1: process termination immediately after successful snapshot replacement."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def run(args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, env=env)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(Path("yim-001/src").resolve()) + os.pathsep + env.get("PYTHONPATH", "")

    with tempfile.TemporaryDirectory(prefix="tho015-probe1-") as directory:
        root = Path(directory)
        store = root / "memory.json"
        content = root / "content.json"
        payload = {"probe": "THO-015-Probe-1", "value": [1, 2, 3]}
        content.write_text(json.dumps(payload), encoding="utf-8")

        initialized = run([sys.executable, "-m", "yim001", "init", str(store)], env)
        if initialized.returncode != 0:
            raise RuntimeError(f"init failed: {initialized.stderr}")

        child_code = r"""
import os
import yim001.storage

real_replace = os.replace
def replace_then_exit(source, destination):
    real_replace(source, destination)
    os._exit(73)

yim001.storage.os.replace = replace_then_exit
from yim001.__main__ import main
raise SystemExit(main(["create", STORE, CONTENT]))
""".replace("STORE", repr(str(store))).replace("CONTENT", repr(str(content)))

        crashed = run([sys.executable, "-c", child_code], env)
        after_crash_bytes = store.read_bytes()
        after_crash = json.loads(after_crash_bytes)
        crash_records = after_crash["records"]
        crash_versions = after_crash["versions"]

        retried = run([sys.executable, "-m", "yim001", "create", str(store), str(content)], env)
        final_bytes = store.read_bytes()
        final = json.loads(final_bytes)

        identities = {
            "record_ids": [row["record_id"] for row in final["records"]],
            "version_ids": [row["version_id"] for row in final["versions"]],
        }
        contents = [row["content"] for row in final["versions"]]
        observations = {
            "crash_exit": crashed.returncode,
            "crash_stdout": crashed.stdout,
            "crash_stderr": crashed.stderr,
            "records_after_crash": len(crash_records),
            "versions_after_crash": len(crash_versions),
            "snapshot_sha256_after_crash": hashlib.sha256(after_crash_bytes).hexdigest(),
            "retry_exit": retried.returncode,
            "retry_stdout": retried.stdout,
            "retry_stderr": retried.stderr,
            "records_after_retry": len(final["records"]),
            "versions_after_retry": len(final["versions"]),
            "identities": identities,
            "contents_identical": len(contents) == 2 and contents[0] == contents[1] == payload,
            "final_snapshot_sha256": hashlib.sha256(final_bytes).hexdigest(),
        }
        print(json.dumps(observations, indent=2, sort_keys=True))

        passed = (
            crashed.returncode == 73
            and len(crash_records) == 1
            and len(crash_versions) == 1
            and retried.returncode == 0
            and len(final["records"]) == 2
            and len(final["versions"]) == 2
            and len(set(identities["record_ids"])) == 2
            and len(set(identities["version_ids"])) == 2
            and observations["contents_identical"]
        )
        print("THO015_PROBE1_RESULT=" + ("REPRODUCED" if passed else "NOT_REPRODUCED"))
        return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
