import subprocess
import tempfile
import unittest
from pathlib import Path

from adaptive_filesystem_runtime.git_transport_publication import (
    GitTransportRefAdapter,
)
from adaptive_filesystem_runtime.publication import (
    PublicationStatus,
    publish_once,
)


def git(cwd, *args):
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise AssertionError(f"git {args!r} failed: {result.stderr}")
    return result.stdout.strip()


class GitTransportPublicationIntegrationTests(unittest.TestCase):
    def test_stale_lease_rejects_overwrite_on_local_bare_remote(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bare = root / "remote.git"
            seed = root / "seed"
            stale = root / "stale"
            newer = root / "newer"

            git(root, "init", "--bare", str(bare))
            git(root, "clone", str(bare), str(seed))
            git(seed, "config", "user.name", "Publication Test")
            git(seed, "config", "user.email", "publication-test@example.invalid")
            (seed / "state.txt").write_text("base\n", encoding="utf-8")
            git(seed, "add", "state.txt")
            git(seed, "commit", "-m", "base")
            base = git(seed, "rev-parse", "HEAD")
            git(seed, "branch", "-M", "main")
            git(seed, "push", "-u", "origin", "main")
            git(root, "--git-dir", str(bare), "symbolic-ref", "HEAD", "refs/heads/main")

            git(root, "clone", str(bare), str(stale))
            git(root, "clone", str(bare), str(newer))
            for clone in (stale, newer):
                git(clone, "config", "user.name", "Publication Test")
                git(clone, "config", "user.email", "publication-test@example.invalid")

            # Create a stale candidate from the original base.
            (stale / "state.txt").write_text("stale candidate\n", encoding="utf-8")
            git(stale, "add", "state.txt")
            git(stale, "commit", "-m", "stale candidate")
            stale_candidate = git(stale, "rev-parse", "HEAD")

            # An independent publisher advances the remote first.
            (newer / "state.txt").write_text("newer external update\n", encoding="utf-8")
            git(newer, "add", "state.txt")
            git(newer, "commit", "-m", "intervening update")
            newer_head = git(newer, "rev-parse", "HEAD")
            git(newer, "push", f"--force-with-lease=refs/heads/main:{base}", "origin", "HEAD:refs/heads/main")

            adapter = GitTransportRefAdapter(stale, "origin")
            result = publish_once(adapter, "main", base, stale_candidate)

            self.assertEqual(result.status, PublicationStatus.STALE_HEAD)
            self.assertEqual(result.observed_head, newer_head)
            self.assertEqual(adapter.read_head("main"), newer_head)
            self.assertNotEqual(adapter.read_head("main"), stale_candidate)

    def test_current_lease_can_publish_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bare = root / "remote.git"
            work = root / "work"
            git(root, "init", "--bare", str(bare))
            git(root, "clone", str(bare), str(work))
            git(work, "config", "user.name", "Publication Test")
            git(work, "config", "user.email", "publication-test@example.invalid")
            (work / "state.txt").write_text("base\n", encoding="utf-8")
            git(work, "add", "state.txt")
            git(work, "commit", "-m", "base")
            base = git(work, "rev-parse", "HEAD")
            git(work, "branch", "-M", "main")
            git(work, "push", "-u", "origin", "main")
            (work / "state.txt").write_text("candidate\n", encoding="utf-8")
            git(work, "add", "state.txt")
            git(work, "commit", "-m", "candidate")
            candidate = git(work, "rev-parse", "HEAD")

            adapter = GitTransportRefAdapter(work, "origin")
            result = publish_once(adapter, "main", base, candidate)
            self.assertEqual(result.status, PublicationStatus.PUBLISHED)
            self.assertEqual(adapter.read_head("main"), candidate)


if __name__ == "__main__":
    unittest.main()
