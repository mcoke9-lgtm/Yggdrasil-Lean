# Nine Builder Conversation Protocol

**Status:** Implemented as a versioned, typed contract, local append-only SQLite journal, single-process coordinator, role-specific prompt factory, strict parser, bounded peer routing, compatible provider adapter, Android Keystore-backed provider settings, and a consent-gated Nine Builder conversation screen; device-level live endpoint verification and independent Builder workers remain pending.
**Protocol version:** 3 (reads protocol versions 1 and 2; new interruption/incident turn types are version 3)
**Authority:** The user remains director and final authority for consequential commitments.

## Purpose

Give the Nine Builders a shared way to contribute, question, challenge, answer, hand work to one another, acknowledge receipt, reconcile differences, and record decisions without flattening their distinct responsibilities.

This protocol is transport-neutral. It can be used by the current single-process prototype and later by separate workers or machines without changing the meaning of a conversation turn.

## Participants

The conversation has ten possible participants: the user (`USER`) and the Nine Builders. The user is a first-class speaker and recipient in the protocol, not an out-of-band annotation. User-authored directions, questions, corrections, and decisions can therefore be preserved in the same thread as Builder contributions and responses. The user remains the director and final authority for consequential commitments.

## The Nine Builder voices

| Builder | Primary responsibility |
|---|---|
| Huginn | Architecture, objectives, and scope |
| Muninn | Memory, continuity, provenance, and recovery |
| Kvasir | Research, evidence, and structured knowledge |
| Mari | Relationships, interfaces, integration, and distributed cooperation |
| Höðr | Implementation, execution, and testing |
| Týr | Integrity, permissions, and security |
| Loki | Alternatives, innovation, and failure recovery |
| Odin | Integration, reconciliation, and dependencies |
| Baldr | Independent verification against requirements |

Each role keeps its own primary responsibility. Any Builder may contribute, ask questions, challenge another Builder, or identify a risk outside its primary area. A primary responsibility is an accountability anchor, not a conversational restriction.

## Turn structure

Every turn has:
- protocol version;
- thread ID and unique turn ID;
- speaking participant (the user or a Builder);
- optional recipient participant (the user or a Builder);
- optional referenced earlier turn;
- turn type;
- explicit evidence status;
- timestamp;
- bounded text body.

Turn types:
- **CONTRIBUTION** — add an observation, proposal, analysis, or work result.
- **QUESTION** — ask a named Builder for a response.
- **CHALLENGE** — contest a specific earlier turn; the challenged Builder remains able to answer.
- **RESPONSE** — answer a named Builder and point to the turn being answered.
- **HANDOFF** — transfer a bounded task or question to another Builder, referencing its origin.
- **ACKNOWLEDGEMENT** — confirm receipt of a handoff or response; receipt is not agreement.
- **RECONCILIATION** — explain what is resolved, still disputed, or awaiting evidence, referencing the relevant turn.
- **INTERRUPTION** — pause the current Builder round when a method is blocked, circular, stale, unauthorized, or materially unsuitable. It is directed to the user, references an earlier turn, and must state evidence, impact, unknowns, a feasible alternative, required recheck/authorization, and resume conditions. The coordinator stops remaining Builder calls in that round and preserves the interruption as an append-only journal turn.
- **IMPROVEMENT_INCIDENT** — create a structured, append-only incident record for a failure or near miss. Its JSON body records summary, observed and expected behavior, evidence, unknowns, suspected causes, next step, and an open status (`OPEN_DIAGNOSIS` or `OPEN_REVIEW`). The coordinator stops remaining Builder calls in that round. This is a journal event, not proof that the six-stage review has completed.
- **CORRECTION_PLAN** — record a structured proposed correction linked directly to an improvement incident. It begins as PROPOSED and includes an objective, acceptance criteria, steps, owner, and rollback condition.
- **CORRECTION_UPDATE** — append a structured lifecycle milestone for the same correction: IMPLEMENTED, TESTED, VERIFIED, or user-authored ADOPTED. Each update links to the immediately preceding lifecycle record. Builders cannot create ADOPTED.
- **DECISION** — record a proposed or user-authorized conclusion. A DECISION turn is a record, not an execution command.

## Evidence vocabulary

- **PROPOSED** — suggested, not yet exercised.
- **OBSERVED** — directly observed, with the observation context recorded.
- **IMPLEMENTED** — implementation work is recorded with an artifact reference; this does not mean it has passed tests.
- **TESTED** — a specified test was run; its scope and result must be stated.
- **VERIFIED** — supporting evidence has been checked against the relevant acceptance criteria by an authorized independent reviewer.
- **ADOPTED** — the user explicitly accepts a verified correction as project practice; it does not authorize deployment or external action.
- **USER_CONFIRMED** — the user reports the result; it is not described as independently observed.
- **DISPUTED** — a material disagreement or contradictory evidence remains.
- **UNKNOWN** — not established.

Evidence status applies to the claim in that turn, not to the speaker's identity or the entire thread. A later turn may correct or supersede a claim; prior turns remain in history.

## Conversation rules

1. **Distinct voices:** identify the participant speaking; Builder turns retain their distinct identities. Do not silently merge separate contributions into a generic collective voice.
2. **Direct dialogue:** address another participant by recipient and reference the turn being answered or challenged.
3. **Disagreement is retained:** a challenge does not delete the original claim. Record the response and any remaining dispute.
4. **Handoffs are explicit:** name the receiving Builder, identify the referenced task/context, and require an acknowledgement before treating receipt as confirmed.
5. **Reconciliation does not erase provenance:** Odin may synthesize the current position, but the source contributions and unresolved differences remain visible.
6. **Verification is independent:** Baldr checks conclusions against stated requirements and evidence; authorship alone is not verification.
7. **Memory carries context:** Muninn preserves thread identity, references, evidence status, decisions, and recovery context.
8. **Scope stays bounded:** Huginn keeps the question and acceptance criteria visible; Mari checks interfaces and relationships; Kvasir distinguishes sources from interpretation; Höðr reports what was actually implemented and tested; Týr checks permissions and integrity; Loki tests alternatives and failure paths.
9. **User authority is explicit:** no Builder gains authority from being named, receiving a handoff, or recording a DECISION turn.

## Validation rules implemented in code

The BuilderConversationProtocol validator checks:
- supported protocol version and required fields;
- non-empty bounded body;
- required recipient and reply references for directed turn types;
- no self-addressing;
- unique turn IDs and one thread identity;
- strictly increasing timestamps in supplied thread order;
- replies and handoffs reference earlier turns;
- responses, challenges, and acknowledgements name the participant who authored the referenced turn; responses and acknowledgements to a directed question, challenge, or handoff must come from the participant originally addressed; a handoff names its new recipient while retaining a reference to its source turn.

Protocol version 2 added USER as a first-class participant. Version 3 adds INTERRUPTION and IMPROVEMENT_INCIDENT turn types and coordinator stop behavior. Version 4 adds CORRECTION_PLAN, CORRECTION_UPDATE, and the IMPLEMENTED / ADOPTED evidence states. Versions 1 through 3 remain readable so existing local history can be recovered; new turns use version 4. This is an additive turn-contract change; no SQLite column migration is required.

The validator is structural. It does not prove the truth of a claim, authenticate a remote sender, call a model, or execute work. The local journal persists validated turns in a private SQLite database, preserving insertion order and thread provenance across store closure and reopening. Appends are transactional; invalid turns are rejected before commit. The public store API exposes reads and appends only, not update or delete operations. The journal is currently device-local: it does not synchronize across devices, encrypt records independently of Android's app-private storage protections, authenticate remote senders, or provide a backup/export format. Database schema changes require an explicit migration; the current upgrade path deliberately fails rather than silently discarding history.

## Single-process coordinator

`BuilderConversationCoordinator` accepts a user message and a selected set of Builder roles. It records the user as `USER`, passes the current immutable thread context to each selected role through the `Responder` interface, and stores successful typed contributions against their referenced turns. It invokes selected roles sequentially; later roles receive earlier successful replies in their context. Typed replies can address another Builder, ask a question, challenge a cited turn, answer a peer, or hand off work. The coordinator routes addressed turns to selected recipients, including a bounded follow-up to an already-run Builder. Each Builder is limited to two turns per submission to prevent unbounded loops. Any Builder may issue an INTERRUPTION or IMPROVEMENT_INCIDENT addressed to the user and referencing an earlier turn. Either event immediately halts the remaining Builder calls in that submission. CORRECTION_PLAN and CORRECTION_UPDATE turns are validated and appended without halting the review round; the result reports the interrupting Builder, event type, and selected roles not run. The event remains in the append-only journal and is displayed in conversation history. This is prompt-guided model behavior plus a deterministic coordinator stop gate: it does not guarantee a model will recognize every unsuitable method, automatically initiate a peer-review round, or enforce interruption outside this coordinator. A recipient not selected for the round, or one that reaches its turn limit, is reported in `unroutedRecipients`; it is not silently treated as having received the handoff. The coordinator preserves successful work if another role fails and reports the failed roles. The responder is deliberately injectable: tests use a deterministic fake, while a real model adapter has not yet been selected or implemented. `BuilderPromptFactory` creates separate role-specific system instructions and JSON-serialized conversation context for each Builder, preserving turn metadata and treating historical bodies as data rather than higher-priority instructions. The prompt factory and parser do not call a model. The current routing is bounded and single-process; it is not evidence of independent agents.

## Methodology interruption and improvement incidents

The role prompt authorizes every Builder to interrupt a blocked, circular, stale, unauthorized, or materially unsuitable method. An interruption must identify the affected earlier turn, evidence/source/time, impact, unknowns, feasible alternative, required recheck or authorization, and resume condition. A structured improvement incident must include `incident_summary`, `observed_behavior`, `expected_behavior`, `evidence`, `unknowns`, `suspected_causes`, `proposed_next_step`, and `status`; the parser accepts only `OPEN_DIAGNOSIS` or `OPEN_REVIEW`. The coordinator persists either event as an ordinary append-only typed turn and stops the current round. Subsequent peer challenge, correction, testing, verification, and adoption remain separate work and must be recorded as they occur. The current runtime does not yet automate the entire six-stage protocol or write incident files directly to the repository.

## Role-specific prompt boundary

`BuilderPromptFactory` creates a model-neutral prompt for any of the nine roles. It includes the Builder's display identity and primary responsibility in the system instruction, states the user's director authority, prohibits fabricated work/verification, and serializes the thread as JSON with turn IDs, speaker, recipient, type, evidence status, reply reference, and body. It validates that the triggering turn is the latest turn in a valid single-thread context. Historical message bodies are data, not instructions that can override the role and authority rules. This is a prompt-construction boundary only: it does not choose a provider, store API credentials, make network requests, or guarantee that a model will follow the instructions. `BuilderReplyParser` validates the required JSON response fields and the protocol's known participant, turn-type, and evidence-status values; the coordinator then validates each constructed turn against the full thread before it is persisted.

## Provider-independent model boundary

The model integration is split into four replaceable parts:

- `BuilderModelProvider` is the transport/model adapter contract. A hosted API adapter and a local-model adapter can implement the same request/completion interface.
- `BuilderProviderRegistry` holds only explicitly installed adapters.
- `BuilderProviderRouter` maps each Builder role to an explicitly selected provider. There is no silent fallback: a missing route fails visibly rather than sending conversation data to an unintended service.
- `BuilderModelResponder` connects the coordinator to the role-specific prompt factory, selected provider, and strict reply parser.
- `OpenAiCompatibleModelProvider` supports hosted HTTPS endpoints and local `http://localhost` endpoints using the common Chat Completions format. `UrlConnectionModelTransport` bounds response size, uses timeouts, and disables redirects.
- `SecureBuilderProviderSettings` stores provider metadata in app-private preferences and encrypts API tokens with AES-GCM using a non-exportable Android Keystore key. `buildProvider()` constructs an adapter without initiating a request.
- The Android console exposes provider setup and removal. The token field is masked; leaving it blank preserves an existing credential only when the provider ID and endpoint are unchanged. Changing destination requires a new token to prevent accidental credential forwarding. Saving settings does not test or contact the endpoint.

Provider requests carry the system instruction, structured conversation payload, and a bounded output-token limit. Provider completions return raw response text, which must pass the same typed parser before the coordinator can persist a turn. Provider identity and model ID are adapter metadata; credentials, endpoint configuration, and transport are deliberately not part of conversation turns or the journal.

The compatible HTTP adapter, Android provider settings, and conversation screen are implemented but deliberately unconfigured: no endpoint or API key has been entered, and no live network request has been made in this development session. HTTPS is required for hosted services; cleartext is permitted only for `localhost` in Android's network security configuration. URLs containing user information or query parameters are rejected. Redirects are disabled, responses are size-limited, timeouts are explicit, and HTTP error messages do not echo provider response bodies. API tokens are encrypted with AES-GCM under an Android Keystore key and are excluded from the conversation journal. The conversation screen sends only after the user presses Send and accepts an endpoint/model-specific disclosure describing the thread history and role prompts that will be transmitted. All nine roles are selected by default but can be deselected. Selected roles run sequentially through the coordinator; successful turns are persisted as they complete and the history remains viewable. Device-level testing, explicit cancellation, and verification against a real hosted/local endpoint remain pending.

## Shared operational limits context

Before a conversation round is dispatched, `BuilderConversationActivity` loads one bounded operational-limits JSON snapshot from the app asset. `BuilderOperationalLimits` validates the register schema, evaluates evidence freshness (seven-day default), rejects partial or inconsistent capacity arithmetic, and marks absent capabilities as `RECHECK_REQUIRED`. The same serialized snapshot is passed through `BuilderModelResponder` and `BuilderPromptFactory` to every selected Builder in that round. Each role is instructed to distinguish `BLOCKED`, `RECHECK_REQUIRED`, `PROCEED_WITH_CONSTRAINTS`, and `PROCEED`, avoid repeated blocked offers, and preserve the user's authorization boundary.

The bundled scheduled-task entry records a historical service response of 3/3 occupied slots on 2026-11-05. It is not live ChatGPT account telemetry. Once older than seven days it becomes `RECHECK_REQUIRED`, not a permanent block. An unlisted capability is unknown. A fresh connected service check is still required before relying on dynamic account/provider capacity.

This is a model-input and recommendation gate, not a general external-action interceptor. The current runtime does not expose arbitrary external action execution. The operational snapshot is loaded once per round and shared across role prompts, but is not yet written into the append-only conversation journal as a separately auditable snapshot record. Future work should add a versioned snapshot reference/hash to round metadata before introducing autonomous action execution.

## First conversation acceptance exercise

Run a scripted conversation with all nine voices represented and these observable stages:
1. Huginn states a bounded question and acceptance criteria.
2. Kvasir contributes sourced findings and labels uncertainty.
3. Loki challenges one material assumption.
4. The original contributor responds.
5. Mari identifies interface or relationship effects.
6. Týr checks authority and safety boundaries.
7. Höðr proposes and reports a bounded implementation/test.
8. Muninn records the handoff, provenance, and continuity checkpoint.
9. Odin reconciles the thread; Baldr independently checks the conclusion.
10. The user accepts, rejects, or revises any consequential decision.

The exercise is a protocol test, not evidence of nine independently executing agents. The first runtime test should use one process and explicit simulated voices. Later tests can replace roles with independent workers while retaining the same message contract.

## Non-goals and safety boundaries

- No model-provider selection or API credentials are added here.
- No external contact, purchases, deployment, destructive changes, or permission escalation is authorized.
- No Builder may claim to be a separately running agent until separate worker processes and their identity/isolation are implemented and verified.
- No changes are made to Builder Host production signing, Scheduler behavior, or production data.

## Local conversation journal

`BuilderConversationStore` is a SQLite-backed, append-only local journal. It stores the complete typed turn fields, including thread and turn IDs, speaker (user or Builder), recipient, reply reference, turn type, evidence status, timestamp, and body. A new append is validated against the existing thread inside a database transaction before insertion. Reads return immutable snapshots in insertion order. The journal has no public update/delete method. Its current schema is version 1; schema upgrades must be implemented as explicit migrations.

`BuilderConversationCoordinator` records a user turn, routes the thread context to selected Builder roles through an injectable responder, and appends each successful response separately. A failed responder does not erase the user turn or other successful responses. The coordinator currently runs in one process and has no concrete model-provider adapter. Background workers, remote transport, cross-device synchronization, and an automatic backup mechanism remain future capabilities and must not be inferred from local persistence.

## Integrity checkpoint

- **Count:** twelve turn types, including interruption, incident, and correction lifecycle events; nine evidence states; nine Builder roles plus the user as director (ten participants).
- **Role mapping:** all nine primary responsibilities are represented once.
- **Omissions/duplicates:** the user is a first-class participant; receipt is distinct from agreement; reconciliation is distinct from decision; a decision record is not an execution permission.
- **Evidence:** validator, journal, coordinator, role-specific prompt factory, typed response parser, bounded peer routing, provider registry/router/responder boundary, and the Android JVM unit-test suite plus the compatible hosted/local HTTP adapter, Android Keystore-backed credential storage, localhost-only cleartext network policy, provider setup UI, and consent-gated conversation/history screen are committed in this branch; CI execution and on-device acceptance testing remain pending. Independent Builder workers remain pending.
- **Correction:** malformed references, duplicate IDs, cross-thread turns, invalid recipient links, and out-of-order timestamps are rejected.
- **Persistence:** source and tests are committed to the development branch only; no deployment or production change.


## User-triggered peer-correction review

A review is initiated only when the user selects the Review latest improvement incident control in the conversation screen. The control locates the latest IMPROVEMENT_INCIDENT in the active thread, creates a new user-authored review request linked by the incident turn ID, and then uses the ordinary provider disclosure and Send authorization path. The incident and review request remain append-only history; no prior record is edited.

Each selected Builder receives the same review-mode marker and incident context. Role duties are separated: Huginn checks scope and acceptance criteria; Muninn checks history/provenance; Kvasir checks evidence; Mari checks interfaces; Höðr checks implementation/tests; Týr checks authority/security; Loki develops alternatives/recovery; Odin reconciles dependencies; Baldr independently checks the correction against evidence and requirements. The Builder named as the incident author may explain but is explicitly prohibited from certifying its own correction. If Baldr is not selected, the prompt requires the review to report independent verification as missing. Review responses remain model-generated claims until evidence-bearing tests or checks are actually performed; agreement does not equal adoption.

The review is still prompt-guided and runs through the existing single-process coordinator. It is not an independent multi-agent adjudication service, and this feature does not automatically change incident status or adopt a correction.


An Android JVM unit-test workflow has also been added at .github/workflows/android-unit-tests.yml. It runs :app:testDebugUnitTest on push, pull request, or manual dispatch. The workflow definition is present in this branch, but no corresponding run has yet been returned by the GitHub run lookup; therefore Android tests remain NOT RUN / NOT VERIFIED.


The coordinator also enforces a narrow verification authority boundary during improvement review: the incident author cannot return a VERIFIED review claim, and only an unaffected Baldr may do so. If Baldr authored the incident, no Builder in that review round may mark the correction VERIFIED; an independent external check or a separately designed alternate verifier is required. Rejected self-certification or non-Baldr verification attempts are recorded as failed-role outcomes rather than appended as accepted VERIFIED turns. This is a deterministic role gate, not proof that Baldr's own evidence assessment is correct.


## Traceable correction lifecycle

A correction is not considered complete merely because a review agrees with it. A review may create a CORRECTION_PLAN linked to the original IMPROVEMENT_INCIDENT. The plan contains a stable correction ID, objective, acceptance criteria, steps, owner, rollback condition, and PROPOSED status. Each subsequent CORRECTION_UPDATE is append-only, retains the same correction and incident IDs, and replies to the immediately preceding lifecycle record.

The only permitted progression is:

1. PROPOSED — a plan exists; no implementation is implied.
2. IMPLEMENTED — a concrete artifact reference is recorded; tests are not implied.
3. TESTED — an actual test name, run/result, and artifact reference are recorded.
4. VERIFIED — an unaffected Baldr checks evidence against the plan's acceptance criteria after testing. If Baldr authored the incident, Baldr cannot verify that correction.
5. ADOPTED — only an explicit user action may append this milestone, after the latest milestone is independently verified.

The coordinator rejects skipped or out-of-order milestones, mismatched correction/incident IDs, incorrect reply references, Builder-authored adoption, and verification by anyone other than an unaffected Baldr. Before recording adoption, the local ledger revalidates the VERIFIED milestone and its provenance. The Android screen offers a separate Adopt latest verified correction (record only) control with a confirmation dialog. Adoption records acceptance of the correction as project practice; it does not deploy code, change settings, contact services, or authorize external actions. All prior milestones remain in the journal.

The lifecycle validator is deterministic, but the quality of the evidence still depends on the referenced artifact and test being real and appropriately scoped. A model-generated statement is not itself proof of implementation, testing, or verification. Device execution of the Android test suite remains pending.
