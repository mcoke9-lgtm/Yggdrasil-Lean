"""Git-transport publication adapter using an explicit force-with-lease.

Unlike GitHub's REST ref endpoint, Git push with an explicit lease asks the
remote Git server to reject the update unless the ref still has expected_head.
This adapter is opt-in and requires a local repository containing desired_head.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

from .publication import RefUpdateRejected


_SHA_RE = re.compile(r"^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")


class GitTransportRefAdapter:
    """Publish refs through Git transport with server-enforced explicit lease."""

    def __init__(self, repository: str | Path, remote: str = "origin"):
        self.repository = str(repository)
        if not isinstance(remote, str) or not remote.strip():
            raise ValueError("remote must be a non-empty name or URL")
        self.remote = remote

    def _run(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *args],
            cwd=self.repository,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def _validate_branch(self, branch: str) -> str:
        if not isinstance(branch, str) or not branch.strip():
            raise ValueError("branch must be a non-empty branch name")
        result = self._run("check-ref-format", "--branch", branch)
        if result.returncode:
            raise ValueError("invalid branch name")
        return f"refs/heads/{branch}"

    @staticmethod
    def _validate_sha(sha: str) -> str:
        if not isinstance(sha, str) or not _SHA_RE.fullmatch(sha):
            raise ValueError("commit SHA must be a full 40- or 64-character hex ID")
        return sha.lower()

    def read_head(self, branch: str) -> str:
        ref = self._validate_branch(branch)
        result = self._run("ls-remote", "--heads", self.remote, ref)
        if result.returncode:
            raise RuntimeError(f"git ls-remote failed: {result.stderr.strip()}")
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) == 2 and fields[1] == ref:
                return fields[0].lower()
        raise RuntimeError(f"remote branch not found: {branch}")

    def compare_and_swap(
        self, branch: str, expected_head: str, desired_head: str
    ) -> None:
        ref = self._validate_branch(branch)
        expected = self._validate_sha(expected_head)
        desired = self._validate_sha(desired_head)
        if expected == desired:
            raise ValueError("desired_head must differ from expected_head")

        # Explicit expected object ID is sent to the Git server as a lease.
        result = self._run(
            "push",
            "--porcelain",
            f"--force-with-lease={ref}:{expected}",
            self.remote,
            f"{desired}:{ref}",
        )
        if result.returncode == 0:
            return

        # Confirm stale lease by reading the remote. If the ref cannot be read,
        # preserve ambiguity rather than incorrectly labelling it stale.
        try:
            observed = self.read_head(branch)
        except Exception as exc:
            raise RuntimeError(
                f"git push outcome unresolved; remote ref could not be read "
                f"({type(exc).__name__})"
            ) from exc
        # A failed client response may follow a successful remote update.
        # Reconcile the desired head before classifying a changed head as stale.
        if observed == desired:
            return
        if observed != expected:
            raise RefUpdateRejected(
                f"lease rejected: expected {expected}, observed {observed}"
            )
        raise RuntimeError(f"git push failed without head change: {result.stderr.strip()}")
