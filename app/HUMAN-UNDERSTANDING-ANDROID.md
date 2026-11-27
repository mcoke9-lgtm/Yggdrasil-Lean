# Android Human Understanding Register — YIM-001

**Status:** Android SQLite store and portable lifecycle adapter implemented on this branch; automated verification pending.
**Relationship:** Android host implementation of the event contract introduced by the Python YIM-001 Human Understanding Register on `main`.

## Permanent naming rule

- Technical identifiers remain canonical and stable.
- Human-readable names, alternatives, and competing interpretations are attached to technical identifiers; they do not replace them.
- Mari may propose multiple alternatives and identify a preferred option.
- A proposal is not an adoption.
- This Android store deliberately exposes no adoption method. A later trusted Týr/human authorization integration must enforce the approval boundary rather than trusting a caller-supplied boolean.
- Existing source intake packets are not rewritten.

## Android implementation

- `HumanUnderstandingRegisterStore` persists the YIM-001 event envelope in a dedicated private SQLite database.
- Event sequence, event ID, event type, actor, and payload use the same names as the portable Python register.
- Technical IDs are unique; event IDs and proposal IDs are unique.
- Identical retries of the same proposal are idempotent. Reuse of an ID with conflicting content is rejected.
- Proposal validation requires at least two alternatives, a valid preferred option, strengths, limitations, competing interpretations, and source references.
- Export produces a JSON snapshot with the same format identifier and version as the Python register.
- `HumanUnderstandingLifecycle` accepts an optional host-supplied Mari proposal provider. It sends descriptive metadata only; it does not forward the original intake content. If no provider is configured, it reports that Mari was not invoked.

## Character and authority boundaries

- Mari: propose alternatives and interpretations.
- Huginn: source/provenance checks remain a separate responsibility.
- Muninn: append-only history is retained in the register.
- Odin: later host reconciliation must preserve disagreement.
- Týr: the trusted authorization path is not yet connected to this store.
- Human owner: retains adoption authority.

A role label is attribution metadata, not proof that a separate autonomous Character process ran.

## Current limitations

- The Android lifecycle adapter is a callable service boundary; it is not yet invoked automatically by the intake staging UI or Builder runtime.
- No Mari provider is configured by this change. The provider interface is ready for a host adapter, but automatic Mari generation is not claimed.
- No adoption/decline/deferral API is exposed on Android until trusted authorization is implemented.
- Staging and register writes are separate SQLite databases. This change does not claim a cross-database transaction or automatic crash reconciliation.
- No physical-device verification is claimed.
- This branch is based on the Android intake-staging development branch; it does not change `main` or install an APK.

## Verification

The focused Robolectric tests cover event persistence across restart, portable snapshot structure, proposal alternatives, identical retry behavior, conflicting identity rejection, metadata-only provider requests, and explicit missing-provider reporting. GitHub Actions must pass before these tests are described as verified.
