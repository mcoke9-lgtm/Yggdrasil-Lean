# Android Device Self-Reporting Bridge — 001

**Status:** Implemented in source; CI and physical-device verification pending.  
**Branch:** `independent-builder-runtime-foundation-001`  
**Target reported by user:** Samsung SM-G996U, Android 15.  
**Purpose:** Remove the user from routine manual hardware/software inventory. The Android host reports its own execution capabilities to the Builder-native runtime.

## Implemented

- Added `AndroidDeviceInventory`, a permission-light collector using Android platform APIs.
- Reports manufacturer, model, device code, Android release/API level, supported ABIs, available processor count, total/available RAM, and app data-volume total/available storage.
- Runtime health response includes a JSON inventory and advertises `DEVICE_CAPABILITY_INVENTORY`.
- Added a dedicated local IPC request for refreshing device inventory.
- Builder conversation status automatically displays detected device model, Android version, RAM and available storage when the runtime connects.
- Added Robolectric assertions for schema, platform data, memory/storage values, capability declaration, and privacy scope.

## Privacy boundary

The inventory deliberately excludes serial numbers, Android ID, IMEI, phone numbers, account data, location, Wi-Fi identifiers, installed-app inventories, and filesystem contents. No new Android permission is required. The report is generated locally and passed only over the app's existing non-exported Messenger service boundary. It is not uploaded to a cloud endpoint by this change.

## Interoperability contract

The JSON report is versioned (`schema_version: 1`) and describes capabilities, not identity credentials. Other hosts should later publish the same portable envelope with host-specific collectors. A shared coordinator can compare resource requirements against these reports without depending on Android-specific APIs.

Recommended common fields for future host adapters:
- host kind and adapter version;
- OS/runtime version;
- CPU architecture and processor count;
- total and available memory;
- total and available storage;
- available accelerators and inference backends (only when actually detected);
- capability list with evidence source and observation time;
- explicit unavailable/permission-denied states.

## Not implemented or verified

- No automatic network synchronization to cloud or NUC.
- No remote control, ADB pairing, device discovery, or arbitrary app access.
- No model inference capability is claimed by this inventory.
- No actual phone reading has been obtained by this chat; the installed APK must include this change before the Builders can collect the report.
- CI and physical-device testing remain pending.

## Acceptance gates

1. Android build and Robolectric tests pass in CI.
2. APK is signed with the established stable key before asking for installation.
3. After installation, the app reports actual phone values without manual Settings lookup.
4. Values are checked against Android Settings / device diagnostics once as a one-time validation, not as a recurring user task.
5. No personal identifiers or unexpected permissions appear in the report.
6. Later host adapters for free cloud and NUC implement the same portable schema without changing Builder identity or memory.
