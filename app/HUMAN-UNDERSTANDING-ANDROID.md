# Android Human Understanding Register — YIM-001

**Status:** Android SQLite register, staged-intake adapter, and user-confirmed Mari proposal action implemented on this branch. CI verification of the latest revision is pending.

## Permanent naming rule

- Technical identifiers remain canonical and stable.
- Human-readable names, alternatives, and competing interpretations accompany technical identifiers; they do not replace them.
- Mari may propose multiple alternatives and identify a preferred option.
- A proposal is not an adoption.
- This Android implementation exposes no adoption method. A future trusted Týr/human authorization path must enforce approval rather than trust a caller-supplied boolean.
- Original source intake packets remain unchanged.

## Android implementation

- `HumanUnderstandingRegisterStore` persists the YIM-001 event envelope in a private SQLite database.
- Event sequence, event ID, event type, actor, and payload use the portable register's field names.
- Technical IDs, event IDs, and proposal IDs are stable; identical retries do not append duplicate proposals, while conflicting reuse is rejected.
- `StagedIntakeNamingService` creates stable register entries for pending staged packets.
- The MainActivity exposes a user-confirmed action to register pending packets and, when a provider is configured, request Mari naming proposals.
- The provider receives only the derived technical ID, source kind, language, and short derived purpose. It does not receive packet content, source references, or the original queue.
- If no model provider is configured, identity registration still works and the result explicitly reports that Mari was not invoked.
- Snapshot export uses the portable YIM-001 format identifier and version.

## Character and authority boundaries

- Mari: propose alternatives and interpretations.
- Muninn: append-only event history is retained.
- Huginn: provenance checks remain a distinct responsibility.
- Odin: later reconciliation must preserve disagreement.
- Týr: trusted adoption authorization is not yet connected.
- Human owner: retains adoption authority.

A role label is attribution metadata, not proof that a separate autonomous Character process ran.

## Current limitations

- The action is user-triggered; no background or automatic intake processing is claimed.
- Mari uses the existing configured model endpoint, if present. No provider is provisioned or contacted until the user confirms the action.
- No trusted Týr authentication or adoption implementation.
- Intake staging and the Human Understanding Register are separate SQLite databases; no cross-database transaction is claimed.
- No physical-device verification or APK installation is claimed.
- This PR is stacked on the Android intake-staging foundation and does not change `main`.

## Verification

Robolectric tests cover event persistence/restart, snapshot structure, alternatives, retries, identity conflicts, metadata-only provider requests, staged-packet registration, and explicit missing-provider behavior. The latest GitHub Actions run must pass before this revision is described as tested.
