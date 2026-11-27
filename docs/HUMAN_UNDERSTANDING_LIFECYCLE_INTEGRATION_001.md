# YIM-001 Human Understanding Lifecycle Integration 001

**Status:** Implemented and focused tests passed in GitHub Actions. PR #69 remains under review; not merged or wired into Android.  
**Purpose:** Make the Human Understanding Register a reusable part of the Living Tree work-intake lifecycle.

## Standard intake lifecycle

1. Preserve the original intake packet in `WorkIntakeQueue`; do not rewrite source content.
2. Create or validate a stable technical identity in `HumanUnderstandingRegister`.
3. Supply descriptive context to a configured Mari proposal provider.
4. Require multiple human-readable alternatives, meanings, strengths, limitations, and competing interpretations.
5. Append Mari's proposal as a distinct event.
6. Present the proposal for human review; no automatic adoption.
7. Record adoption/decline/deferral through the existing explicit decision API.
8. Keep technical ID canonical and all prior naming events recoverable.

## Adapter

`yim001.lifecycle.HumanUnderstandingLifecycle` connects an existing `WorkIntakeQueue` and `HumanUnderstandingRegister`. A host can inject a Mari proposal provider. If no provider is configured, the adapter creates the technical identity but explicitly reports that Mari was not invoked. This avoids falsely claiming autonomous Character execution.

By default, only descriptive metadata is passed to the provider; original source content is not forwarded. A host must separately authorize any wider context access.

The adapter is idempotent for repeated requests using the same entry identity and proposal ID. A conflicting reuse of an entry ID is rejected.

## Persistence and recovery

The adapter coordinates in-memory objects but does not perform a cross-file transaction. The host must persist both the intake queue and register using their respective save functions, then verify both snapshots. If a crash occurs between writes, the host must reconcile by stable entry and proposal IDs; it must not invent a completed transaction.

Multi-process locking, cross-file atomic transactions, authenticated approvals, and cross-device synchronization remain future host responsibilities.

## Character responsibilities

- Mari: propose names and interpretations; preserve alternatives.
- Huginn: validate source references and factual descriptions.
- Muninn: preserve prior proposals and decisions.
- Odin: coordinate reconciliation without erasing disagreement.
- Týr: enforce the trusted authorization path.
- Human owner: make the adoption decision.

Role labels in records are attribution metadata, not proof that an autonomous Character process executed.

## Acceptance criteria

- Source intake packet remains unchanged.
- Stable technical ID is created once and conflicts are rejected.
- Mari proposal provider is optional and explicitly reported.
- Multiple alternatives and competing interpretations are preserved.
- No proposal automatically becomes adopted.
- Repeated intake does not duplicate an entry or proposal.
- JSON persistence remains the responsibility of the host and is verified separately.

## Current boundary

This is a portable Python integration adapter, not Android integration. It provides a callable lifecycle service for a host to adopt. It does not add a scheduler, background Character runtime, authenticated identity, or multi-device synchronization.
