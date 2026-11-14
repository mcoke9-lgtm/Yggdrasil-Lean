# Memory Access Gate — Android Prototype

**Status:** implementation plan committed to an isolated development branch; no application source code has been added and no security behavior has been tested.

## Purpose

Build a local Android prototype that demonstrates a real operating-system boundary around a small memory store. The prototype is separate from the existing Builder Host, Scheduler, and Key Steward applications.

## Repository boundary

- Development branch: `memory-access-gate-android-prototype-001`
- Base branch: `independent-builder-runtime-foundation-001`
- Existing application modules must remain unchanged.
- Do not modify the protected `main` or `android-scheduler-host-prototype-001` branches.
- Do not change existing package identifiers, signing arrangements, databases, backups, or installed applications.

## Proposed applications

### Memory Gate

- Proposed application ID: `org.yggdrasil.memorygate`
- Owns the protected memory database and the access-decision log.
- Exposes only a deliberately narrow Android bound-service interface.
- Defines a signature-level permission for trusted client applications.
- Verifies the caller identity and independently checks every requested record against the authorization.
- Does not expose database credentials, database files, or unrestricted query operations to clients.

### Memory Client

- Proposed application ID: `org.yggdrasil.memoryclient`
- Submits a specific retrieval request.
- Has no direct database credentials and no shared access to the Gate's private files.
- Receives only the records approved by the Gate.

### Adversarial test client

A separately signed test application should be used to demonstrate that an untrusted application cannot invoke the protected interface or read the Gate's private database. It must not share the trusted client's signing certificate.

## Android enforcement model

1. Store the database in Memory Gate's internal application storage.
2. Set the service to require a signature-level permission.
3. Keep the service non-exported unless cross-application IPC requires it to be exported; if exported, the signature permission remains mandatory. The final manifest and Android behavior must be inspected and tested.
4. Authenticate the calling UID and package/signing identity at the service boundary. Do not trust caller-supplied package names or identity fields.
5. Validate objective, permitted record identifiers, expiry, and stop condition inside the Gate.
6. Use fixed, parameterized record lookups. Do not accept arbitrary SQL or unrestricted search expressions from the client.
7. Record the authorization decision durably before reading protected content. Record the intended release before returning content; if required audit writes fail, fail closed.
8. Treat local append-only logging as tamper-evident only within the application's ordinary permission boundary, not as protection against root or operating-system compromise.

## Initial test data

Use fictional records only:
- one ordinary project fact;
- one historical decision;
- one restricted test record.

## Required tests

- A trusted client can retrieve a specifically authorized record.
- A separately signed untrusted client is denied by Android permission enforcement.
- The untrusted client cannot read the Gate's private database files.
- A trusted client cannot expand a grant to another record.
- Expired, altered, malformed, or absent authorizations are rejected.
- Arbitrary SQL and unrestricted query input are not accepted.
- If the audit log cannot durably record the required event, protected content is not returned.
- Service interruption does not result in an unlogged release.
- The test report distinguishes Android application isolation from resistance to root, device administrator, physical compromise, or signing-key compromise.

## Signing and test caveat

A debug build can be useful for development, but a valid security test must use distinct signing identities for the trusted and adversarial clients. Sharing one debug signing key across both clients would invalidate the adversarial permission test. Never place private signing keys or passwords in source control or chat.

## Acceptance boundary

A test passes only when the Android service permission, caller identity check, private storage boundary, and observed data access all support the result. A UI message saying “denied” is not sufficient evidence.

## Current status

- Isolated branch: created.
- Implementation plan: recorded here.
- Android modules: not created.
- Source code: not added.
- CI build: not run.
- Device installation: not attempted.
- Enforcement: not demonstrated.
