# Android-First Nine-Builder Host Plan

**Status:** Adopted direction; implementation plan proposed. No independent Builder runtime is installed or running on the user's phone.
**Branch:** `independent-builder-runtime-foundation-001`
**Target device:** Samsung Galaxy S21, Android 15 (user-reported).
**Protected branches:** `main` and `android-scheduler-host-prototype-001` remain untouched.

## 1. Product requirement

The user should be able to state an intended outcome in ordinary language. The Builders should determine which technical systems and capabilities are needed, operate within granted permissions, coordinate work, and report the result with evidence. The user should not need to learn application menus, command syntax, APIs, or implementation details for routine authorized work.

The Android phone is the first execution host, not merely a display or remote control. The user-facing conversational layer and the underlying Builder runtime must be replaceable and must not require OpenAI or Google as runtime providers.

## 2. Architecture on the phone

1. **Conversation interface:** a simple native text-first interface, with voice input/output added as independently replaceable capabilities. The interface asks the user only for decisions that cannot safely or accurately be inferred.
2. **Coordinator:** interprets requests, selects Builder responsibilities, tracks task state, and assembles a response. Initially this may be one local process coordinating role modules; it must not claim nine independent agents.
3. **Builder role registry:** nine persistent role definitions with responsibility, permitted capabilities, and handoff rules. Each role can challenge and review other roles.
4. **Capability adapters:** narrow, documented interfaces to local files, app data explicitly exposed to the app, network services, device functions, and future connected nodes. Each adapter declares permissions, side effects, and verification method.
5. **Continuity store:** local, transactional, exportable event history and task records with provenance, schema version, and integrity checks.
6. **Inference adapter:** a replaceable interface for local language models. Model availability is a capability, not the owner of Builder identity or memory.
7. **Background execution:** Android WorkManager for deferrable tasks; foreground services only where Android policy and a genuinely user-visible ongoing operation justify them. No promise of exact scheduling or uninterrupted background execution.

## 3. Reuse and separation from the current Android app

The existing Android project currently provides a native Activity, local SQLite task database, and WorkManager-based GitHub Actions monitor. Its manifest requests Internet, network-state, and notification permissions. Its current app is a scheduler, not a Builder runtime.

Reuse selectively:
- retain the proven Android project/build pipeline as a starting shell;
- preserve the scheduler as one optional capability;
- introduce Builder-specific data structures and services without conflating scheduler records with canonical Builder memory;
- do not turn the current scheduler branch into the runtime branch;
- keep all new work on this independent runtime branch.

## 4. Staged implementation

### Stage A — Local continuity foundation
- Create a versioned Builder identity registry for all nine roles.
- Create a local append-only event journal and task store.
- Define a versioned message envelope: message ID, sender, recipient, timestamp, correlation ID, content type, schema version, and payload.
- Add export/import with integrity verification.
- Add tests for duplicate delivery, process restart, malformed messages, and recovery.
- No model inference or external service is required for these tests.

### Stage B — First working Builder exchange
- Implement two role workers using the same message protocol and shared event store.
- Allow one role to assign a bounded task to the other, receive a result, challenge it, and record reconciliation.
- Add a transparent local demonstration mode with deterministic test tasks.
- Do not describe role modules as independent AI agents until they are separately scheduled processes with isolated identities and reproducible tests.

### Stage C — Conversational control
- Add a text interface that accepts a natural-language request and shows task progress and evidence.
- Route requests only to implemented capability adapters.
- Where a capability is absent, explain the gap in ordinary language and offer a feasible next step.
- Use confirmation gates for destructive changes, purchases, external communication, sensitive permissions, or other consequential operations.
- Keep the user out of technical menus for routine authorized operations.

### Stage D — Local inference
- Benchmark the actual Galaxy S21 memory, storage, thermals, battery use, and supported acceleration before selecting a model.
- Add at least one local inference backend behind the adapter.
- Verify operation with network access disabled and with OpenAI/Google credentials absent.
- Keep a deterministic fallback so the app remains inspectable if the model is unavailable.
- Never assume that the phone can run nine substantial models concurrently.

### Stage E — Independent voice
- Add speech recognition and speech synthesis as separate adapters.
- Check Android on-device recognition availability; do not treat the default recognizer as offline merely because it is built into Android.
- Test voice input and output in airplane mode, record the engine/model and licenses, and retain text as a fallback.
- Google Voice Access may remain a temporary accessibility aid but is not the permanent Builder voice architecture.

### Stage F — External systems and additional nodes
- Add connectors only through explicit, scoped capabilities with clear read/write distinctions.
- Prefer open, documented protocols and portable data formats.
- Add another user-controlled node only after local identity, event sync, conflict handling, authentication, and recovery have been tested.
- Keep the phone useful when the second node or an external service is unavailable.

## 5. Permission and user-experience model

Capabilities are declared individually, not granted implicitly by a Builder role name:
- **Read:** inspect specifically granted data.
- **Prepare:** draft or stage a change without applying it.
- **Write:** make a reversible, bounded change within an approved scope.
- **Consequential:** requires explicit confirmation at the point of action.

Every operation records the request, responsible role, capability used, target, outcome, and verification evidence. A successful API response alone is not proof that the intended real-world outcome occurred.

The conversational interface should communicate uncertainty and ask one concise question only when a missing detail blocks safe progress.

## 6. Android-specific constraints to test

- Doze, battery optimization, app standby, process death, reboot, and network loss can delay or stop background work.
- WorkManager is appropriate for deferrable work, not real-time agent continuity.
- Long-running foreground work is visible to the user and subject to Android foreground-service rules.
- Private app storage is protected by Android's application sandbox but can still be lost on uninstall or device failure; export and backup are required.
- Local inference may be limited by RAM, thermal throttling, storage, and battery.
- Access to other applications is constrained by Android sandboxing and permissions. Do not promise universal control of arbitrary apps.
- Voice services may silently depend on network services unless explicitly tested offline.

## 7. Acceptance gates

1. Nine role identities and responsibilities persist across app restart.
2. Local event history exports and restores with matching integrity verification.
3. Two role workers exchange messages and reconcile a disagreement without duplicating an action.
4. The user can submit a simple natural-language request and receive a truthful status report.
5. No network/provider credentials are needed for the local continuity and role-exchange tests.
6. Local model operation is verified with network disabled before it is called offline or provider-independent.
7. Voice is called independent only after airplane-mode recognition and synthesis tests.
8. Permissions prevent unauthorized writes and consequential actions.
9. Android background limitations and recovery behavior are documented from actual device tests.

## 8. Builder contributions and challenge rights

- **Huginn:** define scope and ensure the user-facing outcome remains the organizing requirement.
- **Muninn:** own continuity, event history, provenance, export, and recovery.
- **Kvasir:** research Android and model options; distinguish source-backed facts from assumptions.
- **Mari:** co-design the conversation/capability interfaces and interoperability architecture from the outset; challenge designs that make the user manually bridge systems.
- **Höðr:** implement the Android services, role exchange, and tests.
- **Týr:** define capability boundaries, confirmation gates, and audit integrity.
- **Loki:** test alternatives, degraded modes, and recovery paths.
- **Odin:** reconcile interfaces and dependencies across the stages.
- **Baldr:** independently check each acceptance gate and reject unsupported completion claims.

## 9. Current evidence checkpoint

**Inspected source:** the independent runtime branch contains the Android scheduler project. The manifest currently declares Internet, network-state, and notification permissions. The Activity is a scheduler UI; the SQLite database stores scheduler tasks and recent runs; WorkManager performs periodic GitHub Actions checks.

**Not yet implemented:** Builder registry, Builder event journal, role workers, conversational command interface, local model adapter, independent voice, or external capability framework.

**Not yet verified on the physical phone in this phase:** local model performance, on-device speech recognition, offline voice, process recovery for Builder services, or background execution guarantees.

**Integrity:** one phone target; nine defined roles; scheduler kept distinct from Builder runtime; proposed and inspected evidence clearly separated from implemented/tested/verified status.