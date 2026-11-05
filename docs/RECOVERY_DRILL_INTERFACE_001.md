# Recovery Drill Interface 001

## User flow
1. From the Nine Builder Conversation screen, select **Run recovery drill**.
2. Android's system document picker opens. The user selects an existing Yggdrasil JSON recovery export.
3. The app reads the selected document without modifying it and assesses the in-memory JSON.
4. A report dialog displays readiness, integrity, checksum, provenance state, source-comparison state, findings, and an explicit no-restore/no-overwrite/no-delete statement.

## File handling
- Uses Android Storage Access Framework `ACTION_OPEN_DOCUMENT`; no broad storage permission is requested.
- Accepts JSON MIME type.
- Reads at most 25 MiB.
- Does not copy the backup into app storage, upload it, retain its URI, or modify the source document.
- Invalid JSON, unreadable files, and oversized files produce a clear error dialog.

## Assessment scope and limitation
This first interface pass invokes the recovery assessor without an independently retained source-event capture. Therefore, a structurally valid backup with available provenance is expected to report `ready_with_limitations` and `independent_source_capture_not_supplied`, not full `ready`. A later interface can add a separately selected source-capture file once its format and provenance are specified.

## Safety
The drill only reads and assesses. It does not restore, overwrite, delete, change journal state, or modify device settings. The report is not authorization to restore.
