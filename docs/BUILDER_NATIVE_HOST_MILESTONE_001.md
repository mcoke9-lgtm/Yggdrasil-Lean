# Builder-Native Host — Milestone 001

**Status:** Initial implementation committed; CI and physical-device verification pending.
**Development branch:** `independent-builder-runtime-foundation-001`
**Target:** Galaxy S21, Android 15 (user-reported), phone-first.
**Protected:** `main` and `android-scheduler-host-prototype-001` remain unchanged.
**Director:** User retains authority over consequential actions.

## 1. Three-host architecture

1. **ChatGPT host:** optional reasoning, research, language, and collaboration.
2. **Android host:** user-facing phone application, local UI, and Android platform capabilities.
3. **Builder-native host:** a separately identified runtime process that owns Builder runtime identity and exposes a versioned local interface.

The third host is initially deployed as a dedicated Android service process on the Galaxy S21. This is a logical/runtime separation on the same physical phone, not a claim that three separate computers or operating systems already exist. The design should remain portable to another user-controlled machine later.

## 2. First milestone scope

Implemented in this milestone:
- Dedicated Android service process name: `org.yggdrasil.builderhost:builder_runtime`.
- Service is not exported to other applications; binding is limited to this app's UID.
- Separate SQLite database: `yggdrasil_builder_runtime.db`.
- A stable UUID runtime identity is created once and recovered after service/process restart.
- Versioned Messenger IPC contract.
- Health response includes runtime identity, identity creation time, process start time, process name, nine registered Builder role identifiers, and currently implemented capabilities.
- Current capabilities are deliberately limited to persistent runtime identity, health status, and local Android IPC.
- No model inference, external network listener, remote node trust, or external action is introduced.

## 3. Process boundary and authority

The service is hosted in a distinct Android process using `android:process`, while remaining inside the same signed application package and Android sandbox. The service is not a public network service and is not exported. Android remains responsible for process lifecycle; a bound service is not an always-running daemon.

The Builder-native process does not gain authority merely by existing. Future capabilities must be individually declared, permission-checked, audited, and tested. The user remains director and final authority for consequential operations.

## 4. Next stages

- Connect the conversation UI as a client and display the native runtime health state.
- Move canonical Builder event and task coordination behind the runtime IPC boundary.
- Add an explicit append-event command with message identity and idempotency.
- Add local worker scheduling and bounded role-to-role messages.
- Test process death, restart, duplicate delivery, app upgrade, backup/restore, and offline behavior on the actual Galaxy S21.
- Add another machine only after the phone-first runtime has passed its recovery gates.

## 5. Verification status

- **Proposed:** three-host relationship and staged migration.
- **Implemented:** separate Android service process, persistent identity store, health IPC contract, unit tests.
- **Tested:** pending CI.
- **Verified on Galaxy S21:** not yet; no device build/install or process inspection performed for this milestone.
- **Not claimed:** independent AI agents, local inference, autonomous external actions, remote-node operation, or always-on background execution.

## 6. Integrity checkpoint

- **Count:** three hosts; one native process in this milestone; nine role identifiers.
- **Role mapping:** existing nine role registry is reused; runtime identity remains separate from role identity.
- **Omissions/duplicates:** no second copy of canonical conversation history is created; this milestone adds only runtime identity metadata.
- **Evidence state:** implementation committed, test/build outcome pending.
- **Correction:** process separation is not physical-machine separation; revise only with measured deployment evidence.
- **Persistence:** code and this decision record are on the development branch only; no protected branch or separate Scheduler app changed.
