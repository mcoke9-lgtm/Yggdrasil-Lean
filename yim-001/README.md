# Human Understanding Register (YIM-001)

This module gives the Living Tree a portable, append-only register for mapping stable technical IDs to human-readable names and interpretations.

## Run tests

From this directory:

```sh
PYTHONPATH=src python -m unittest discover -s tests -v
```

The implementation uses only the Python standard library and the existing YIM-001 atomic JSON storage helpers.

See [the protocol](../../docs/HUMAN_UNDERSTANDING_REGISTER_AND_MARI_NAMING_PROTOCOL_001.md) for roles, authorization boundaries, the initial MARS-BRIDGE-001 proposal, and known limitations.

**Important:** A successful unit test does not establish Android integration, authenticated authorization, multi-device synchronization, or autonomous Character execution.
