# Phone-to-Tree Local Connection

**Status:** implementation boundary and next-step record; proposed, not adopted or deployed.

## Why a direct import cannot happen yet

The phone intake page creates and exports a versioned JSON queue. The YIM-001 importer consumes that queue together with an existing audited memory snapshot on a machine where the YIM-001 package can run.

The current repository describes YIM-001 as a local, single-writer reference implementation, not a distributed synchronization service. The phone page does not upload data, read ChatGPT history, or write to the audited store. A queue pasted into a chat is inspectable input, not a write to Roots.

The current HTML preview is hosted on a third-party origin. It must not be treated as a trusted bridge to private device storage. Browser storage on that origin is not the audited Tree store and must not be represented as such.

## Required connection shape

The preferred next implementation is a phone-local integration owned by The Living Tree, not a public cloud endpoint and not a GitHub repository used as a memory database.

The first practical host should be an Android application with private app storage and a bundled intake interface. The app should accept a queue through Android's document picker or its bundled interface, validate it, and call the audited import layer locally. It should not require Termux or a public server.

If the existing Android host cannot safely embed or call the YIM-001 implementation, record that as a specific porting boundary before choosing a runtime. Do not silently replace the audited data model with a browser-only database.

## Import contract

1. Accept only an explicitly selected queue file or an in-app queue.
2. Validate format name, supported format version, queue identity, packet identity, timestamps, source kind, language, JSON content, metadata, and preservation marker.
3. Display the proposed primary and contextual Tree placements before committing.
4. Validate every selected logical node against the actual loaded Tree index. Never infer a node ID from a similar label.
5. Preserve source content exactly as submitted. Keep the packet identity and source metadata in provenance.
6. Write the audited memory, provenance ledger, and logical Tree index using the existing atomic snapshot mechanism.
7. Update the queue's imported marker only after the audited store write succeeds.
8. On retry, verify the existing capture by queue and packet identity; never create a duplicate or silently accept changed content or placement.
9. Preserve a pre-write backup and report its identity and digest. Never overwrite an existing backup.
10. Provide a read-back check showing the new record/version, provenance event, primary placement, and contextual placements.

## Security and reliability requirements

- Keep the audited store in Android app-private storage by default.
- No public listener, cloud upload, third-party database, or remote synchronization in the first version.
- No credentials or bearer tokens embedded in the HTML, URL, queue, or source repository.
- Require Android user authorization for import and any restore or replacement operation.
- Enforce a single-writer rule until concurrency control is implemented and tested.
- Make failure states explicit. A failed import must leave the original queue available and must not claim that Roots changed.
- Keep original records append-only; transformations and reconstructions create distinct identities.
- Export and backup remain available so the user is not trapped in the app.

## First acceptance test

Using a harmless test packet, on the Android device:

1. Create and export a queue from the bundled intake page.
2. Import it into a fresh or explicitly selected audited store.
3. Confirm exactly one new record and one `work.captured` provenance event.
4. Confirm the exact source JSON is preserved.
5. Confirm the actual selected primary and contextual node IDs.
6. Restart the app and verify the record and placements remain present.
7. Retry the same packet and verify no duplicate is created.
8. Force an import failure and verify the queue remains pending and the previous audited snapshot remains intact.

## Current test packet checkpoint

The user supplied queue `phone-intake`, version 2, with packet ID `60d49085-b029-400a-adc2-f746b2bc2eb4`. Its source content is:

```json
{
  "text": "Living Tree phone intake test",
  "stage": "first capture"
}
```

It is still `awaiting_placement`. It has not been imported into a persistent audited store.

Proposed primary placement: `roots.archive`.
Proposed contextual placement: unresolved. Existing tests use `branches.projects`, but this does not establish that the node exists in the user's actual Tree. Do not import until the actual Tree index is available and the placement is confirmed.

## Next implementation sequence

1. Inspect the existing Android host application and identify its current private-storage and WebView/file-picker boundaries.
2. Choose the narrowest local integration point that can call the audited import layer without introducing a public network service.
3. Implement the import path and tests against the actual audited schema.
4. Build and verify the Android package in CI.
5. Install and test on the user's phone only after explicit installation authorization.
6. Keep deployment, cloud hosting, repository publication of personal memory, and remote synchronization out of scope unless separately authorized.
