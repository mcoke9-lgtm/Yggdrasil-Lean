# Nine-Model Routing and Baldr Independence

Status: implementation proposal on branch `nine-model-routing-001`; not yet CI-verified, device-tested, independently reviewed, or adopted.

## Purpose

Replace the previous one-provider-for-nine-roles wiring with explicit per-role model routes. Each selected Builder must have a configured route. Missing routes fail closed; the legacy shared provider is not silently reused.

## Configuration

- Each Builder role has its own private SharedPreferences namespace and Android Keystore AES-GCM key alias.
- Existing legacy default-provider settings remain in their original preference file and Keystore alias. They are preserved but are not automatically assigned to any role.
- Configure routes in the Nine Builder Conversation screen using **Configure role-specific model routes**.
- Each role route stores provider ID, endpoint, model ID, and an encrypted token. Blank token retains that role's existing credential only when provider ID and endpoint remain unchanged.
- HTTPS is required for hosted endpoints. HTTP is allowed only for localhost. Saving a route does not contact the endpoint.
- Before a model request, the app requires a route for every selected role, rejects duplicate model IDs among selected roles, and displays all selected provider IDs, model IDs, and endpoints in the data-sharing approval notice.
- Approval is bound to the selected role/provider/endpoint/model set. A changed route requires a new approval.

## Baldr guard

An explicit improvement review requires Baldr to be selected and configured. Baldr's endpoint **and** model ID must both differ from every configured non-Baldr role. Model IDs must also be unique across all selected roles. The check rejects a missing Baldr route, a shared endpoint, or a shared model ID.

This is a minimum configuration separation check, not proof of independent ownership, model lineage, weights, infrastructure, training data, or governance. Provider IDs and model IDs are user-entered labels; the remote provider's claims are not cryptographically attested. A Baldr reply must not be described as independently verified solely because this guard passes.

## Provenance

Each persisted Builder reply is prefixed with `MODEL_PROVENANCE` containing the role, role-scoped provider route ID, and provider-reported model ID. This is an audit hint from the local adapter and remote response, not cryptographic attestation.

## Execution boundary

The current coordinator still runs selected roles sequentially in one Android process. This change creates separate configured model routes and credential namespaces; it does not create nine isolated processes, independent schedulers, or autonomous workers. Provider endpoint reachability is only known after a real request succeeds.

## Tests added

- Role-specific provider identities remain distinct even when provider IDs are reused across roles.
- Missing role routes are reported; no implicit shared-provider fallback is allowed.
- Baldr route is rejected when endpoint or model ID is shared with any configured non-Baldr role.
- A distinct endpoint and model pair passes the minimum configuration check.
- Legacy default provider settings remain separate from role-scoped profiles.
- Model responder persists route and provider-reported model provenance.

## Outstanding verification

1. Run Android unit tests and Lean analysis against the exact branch head.
2. Inspect all call sites for bypasses of role-specific routing and disclosure.
3. Test with independently administered provider accounts/models where available; verify endpoint and returned model identity.
4. Obtain an independent reviewer. The implementation author cannot certify Baldr's independence.
5. On-device testing requires explicit user authorization.
6. Record actual provider ownership, model lineage, data-retention terms, and operational independence as unknown until supported by evidence.

No credentials, endpoints, or paid services are provisioned by this change.
