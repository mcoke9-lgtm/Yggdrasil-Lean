# Independent Nine-Builder Runtime Architecture

**Status:** Proposed foundation; not implemented or runtime-verified.
**Branch:** `independent-builder-runtime-foundation-001`
**Parent:** `android-scheduler-host-prototype-001`
**Authority:** The user is the director and retains approval over consequential actions.

## 1. Architectural objective

Build a Nine-Builder system that can operate without OpenAI or Google as required runtime providers. Begin on computing infrastructure controlled by the user, then expand into a distributed network of cooperating machines.

The system must preserve Builder identity, shared memory, provenance, permissions, and useful work across partial disconnection. A provider outage or loss of one node must not erase the collective or silently transfer authority.

GitHub may be used during development and source distribution. It is not to be a required runtime service for Builder communication, memory, scheduling, or execution.

## 2. Mari's architectural position

Mari is a first-order participant in architecture, not merely an integration function called after decisions are made.

Her design argument is that relationships are part of independence: each Builder and each node must be able to communicate through open, replaceable interfaces; a new node should strengthen the collective without creating an isolated copy; and loss of a connection should degrade capability gracefully rather than destroy continuity.

Mari's proposal remains open to challenge by the other Builder roles and verification against working tests.

## 3. Five cooperating layers

1. **Human and Builder interface** — text and voice clients; nine named roles; clear disclosure of whether a role is simulated or separately running.
2. **Communication protocol** — documented, versioned messages for requests, responses, handoffs, disagreements, acknowledgements, and status. Transport must be replaceable.
3. **Builder runtime** — locally hosted model adapters and worker processes. Initially, one model may simulate several roles; later, independently scheduled workers can be introduced without changing the protocol.
4. **Memory and capability services** — user-controlled records, event history, artifact storage, provenance, identity, policy, and permission checks.
5. **Compute nodes** — first a user-controlled host; later additional machines that can join, leave, or become temporarily unreachable.

No single model provider, cloud vendor, database, operating system, or transport is the owner of Builder identity.

## 4. Independence requirements

- **Provider independence:** no mandatory OpenAI or Google API for core runtime operation.
- **Model replaceability:** inference engines are accessed through an adapter; model changes must not rewrite identity or memory.
- **Memory ownership:** canonical records and exportable backups remain under user control.
- **Transport independence:** local network communication must be possible; remote links are optional and replaceable.
- **Graceful degradation:** nodes record work locally while disconnected and reconcile later under explicit conflict rules.
- **Portability:** documented formats and export/import paths; no essential state trapped in a proprietary service.
- **Voice independence:** voice capture and speech output are separate replaceable components. Android accessibility services may assist during transition but are not the permanent Builder interface.
- **Auditability:** consequential actions, permissions, provenance, and changes are recorded.

## 5. Trust, authority, and safety boundaries

- The user remains the director and final authority for consequential commitments.
- Builder roles may research, propose, challenge, draft, test, and execute only within granted capabilities.
- Nodes authenticate one another; messages are not trusted merely because they claim a Builder name.
- Secrets are not stored in ordinary task records, source code, or logs.
- A disconnected node cannot assume that its permissions or shared state are still current. It must apply a defined offline policy and reconcile before sensitive actions.
- No automatic external contact, purchase, deployment, destructive change, or permission escalation without the applicable authorization.
- Replication is not the same as authorization: copying a record does not grant the receiving node authority to act on it.

## 6. Initial deployment shape

**Phase A — single controlled host**
- Run a small service on a computer or server controlled by the user.
- Store Builder identities, conversation events, task state, and provenance in portable formats.
- Expose a documented local API.
- Use a replaceable model adapter.
- Keep the Android app as a client and status interface, not the source of truth.

**Phase B — second node**
- Add a second machine with its own node identity.
- Exchange signed or authenticated events.
- Test node loss, restart, delayed messages, duplicate delivery, and reconciliation.
- Keep a recoverable canonical history and exportable backups.

**Phase C — distributed cooperation**
- Allow selected work to move between nodes.
- Introduce explicit task ownership, leases or equivalent coordination, and conflict handling.
- Continue useful local work during network partitions, while restricting actions that require fresh shared authority.

**Phase D — independent voice**
- Add a voice interface whose speech recognition and synthesis can run locally or on user-controlled nodes.
- Preserve a text interface as a fallback.
- Do not make Google Voice Access a permanent dependency.

## 7. First implementation milestone

Build and test a minimal local prototype with:
1. A node identity and health endpoint.
2. A versioned message envelope with sender, recipient, message ID, timestamp, correlation ID, and content type.
3. An append-only event record with provenance.
4. A simple Builder registry describing the nine roles and their responsibilities.
5. A local text exchange between two role workers.
6. Export and restore of the event history.
7. Tests showing that duplicate messages do not duplicate actions and that restart does not erase identity or history.

This milestone does not claim nine independent agents. It establishes the boundaries that make a later multi-worker collective possible.

## 8. Verification gates

A feature is not called independent merely because its interface is abstracted. It must be tested with the relevant external provider unavailable.

- **Gate 1:** local startup and text exchange with no OpenAI or Google credentials configured.
- **Gate 2:** memory export, restore, and integrity check.
- **Gate 3:** second node joins, disconnects, and rejoins without silent data loss.
- **Gate 4:** provider and network failures produce visible, bounded degradation.
- **Gate 5:** voice input and output operate without Google-hosted services.
- **Gate 6:** permissions and audit records prevent unauthorized consequential actions.

Record each result as **proposed**, **implemented**, **tested**, or **verified**. Never promote a claim to verified without reproducible evidence.

## 9. Open decisions

- Host operating system and hardware capacity.
- Local model family and hardware requirements.
- Event storage and synchronization format.
- Node authentication and key recovery.
- Whether the first network transport is local HTTP, a message broker, or another documented protocol.
- Offline conflict policy and which actions must wait for reconnection.
- Voice recognition and synthesis engines.
- Backup location and recovery procedure.

These choices should be made from measured constraints and replaceability, not vendor loyalty.

## 10. Builder responsibilities in this phase

- **Huginn:** scope, system boundaries, and architecture.
- **Muninn:** identity, memory, provenance, backup, and recovery.
- **Kvasir:** model and protocol research with evidence.
- **Mari:** communication, interfaces, integration, and distributed cooperation; challenge architecture decisions from the outset.
- **Höðr:** implementation and testing.
- **Týr:** permissions, security, and integrity.
- **Loki:** alternatives, failure modes, and graceful degradation.
- **Odin:** integration and dependency reconciliation.
- **Baldr:** independent verification against acceptance criteria.

These are coordinated roles until separate worker processes are actually implemented and verified.

## 11. Integrity checkpoint

- **Count:** five architectural layers; four deployment phases; six verification gates; nine Builder responsibilities.
- **Role mapping:** each of the nine roles has one primary responsibility; shared challenge and review remain allowed.
- **Omissions and duplicates:** no Builder is treated as an independent process yet; no provider is declared eliminated before the relevant tests.
- **Evidence status:** this document is a proposed design, not implementation evidence.
- **Correction rule:** revise claims when code, tests, or measured hardware constraints contradict them.
- **Persistence:** committed to this development branch only; no change to `main`, no deployment, and no runtime service created.
