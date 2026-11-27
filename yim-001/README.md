# Human Understanding Register (YIM-001)

This module gives the Living Tree a portable, append-only register for mapping stable technical IDs to human-readable names and interpretations.

## Run focused tests

From this directory:

```sh
PYTHONPATH=src python -m unittest discover -s tests -p "test_naming.py" -v
```

The implementation uses only the Python standard library. It writes a portable JSON snapshot using a same-directory temporary file and atomic replacement.

The broader pre-existing `test_intake.py` currently cannot import `yim001.core` in the repository snapshot used for this branch. The focused workflow deliberately tests this new component without concealing that separate baseline issue.

See [the protocol](../../docs/HUMAN_UNDERSTANDING_REGISTER_AND_MARI_NAMING_PROTOCOL_001.md) for roles, authorization boundaries, the initial MARS-BRIDGE-001 proposal, and known limitations.

**Important:** A successful unit test does not establish Android integration, authenticated authorization, multi-device synchronization, or autonomous Character execution.
