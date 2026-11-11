# THO-015 — Probe 3 Symlinked Record Directories

**Probe:** THO-015-PROBE-3  
**Status:** NOT APPLICABLE to the inspected YIM-001 storage architecture  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Source inspected:** `yim-001/src/yim001/storage.py` at branch head before this report

## 1. Planned probe

The portable regression-test plan listed symlinked record directories as Probe 3, subject to applicability. The intended concern was whether record-directory traversal or symlink redirection could cause data to be read from or written outside an authorized store root.

## 2. Architecture observation

The inspected storage implementation persists the entire MemoryStore as one JSON snapshot file:
- `save_store(store, path)` calls `write_json_atomic(path, store.to_snapshot())`.
- `load_store(path)` reads that single path using `read_json_strict`, then reconstructs the MemoryStore.
- Atomic writing creates a temporary file in the destination's parent directory and replaces the destination with `os.replace`.
- No per-record directory layout, record subdirectory traversal, or record-directory discovery exists in this implementation.

## 3. Applicability result

**NOT APPLICABLE** — the specific attack surface described by Probe 3 (symlinked record directories) does not exist in the inspected single-snapshot storage design.

This is not a PASS for symlink safety generally. It does not test or establish behavior for:
- a symlink used as the snapshot file path;
- a symlink used as the parent directory;
- races involving path replacement;
- platform-specific symlink permissions or semantics.

Those are distinct filesystem-path probes and are outside the four-probe scope unless separately authorized.

## 4. Change control

No runtime test was run because the target structure is absent. No YIM-001 source, tests, or specification were modified. This report records an applicability determination only.
