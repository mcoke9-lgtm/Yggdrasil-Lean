# Phase A Provider and Runtime Adapter Inventory

**Status:** Source-code inventory verified from the repository branch; live device configuration and endpoint availability are unknown.

## Observed implementation

| Component | Observed behavior | Boundary |
|---|---|---|
| `BuilderModelProvider` | Provider-neutral request/completion interface; carries system instruction, user message, output-token limit; returns raw response text and model ID | No model weights or local inference engine are included in this interface |
| `BuilderProviderRegistry` | Immutable in-memory registry keyed by provider ID; rejects null and duplicate IDs | Registry entries are constructed by application code; this is not a persistent model/artifact inventory |
| `BuilderProviderRouter` | Explicit per-Builder-role provider selection; rejects missing routes; no implicit fallback | Routing does not yet perform resource-aware model assignment or elastic scale decisions |
| `OpenAiCompatibleModelProvider` | OpenAI Chat Completions-compatible HTTP adapter; credential source and transport injected; supports hosted and compatible local endpoints | API compatibility does not establish that a local model is installed or available |
| `SecureBuilderProviderSettings` | Stores provider ID, endpoint, and model ID in app-private preferences; encrypts token with AES-GCM using a non-exportable Android Keystore key; `readConfiguration()` exposes only metadata and credential-presence boolean | The device-local preferences are not accessible from the repository; actual values remain unknown |
| `BuilderModelResponder` | Creates role-specific prompt from accumulated thread context, routes role to provider, calls provider, and strictly parses response | The same provider can serve multiple roles; independent models/processes are not implied |

## Security and endpoint constraints observed in source

- Hosted endpoints must use HTTPS and require a token.
- HTTP is accepted only for `localhost`.
- Endpoint validation rejects embedded user information, query strings, and fragments.
- The token is supplied at call time through an injected credential source; it is not included in the conversation journal.
- Provider construction does not make a network request.
- Saving provider settings does not itself verify endpoint reachability or model availability.
- The provider settings object stores one provider/model configuration at a time; the registry and router are separate abstractions.

## Inventory conclusions

**Verified from source:**
- The application has a provider-neutral model boundary.
- It has one implemented OpenAI-compatible HTTP adapter.
- It has an explicit provider registry and per-role routing abstraction.
- It has device-local credential storage designed to keep bearer tokens encrypted and out of journal records.
- The current route is static per Builder role; no elastic model allocator is present.

**Not verified:**
- Which provider ID, endpoint, or model ID is currently saved on the user's Android device.
- Whether a token is present, valid, or accepted by a provider.
- Whether any local inference server or model weights are installed.
- Whether a hosted or local endpoint is reachable.
- Model family, parameter count, quantization, tokenizer, context window, license, artifact digest, latency, throughput, RAM, accelerator use, or energy consumption.
- Whether any model meets the proposed capability contracts.

No credential values were requested, read, copied, or committed. The next inventory step, if needed, should export only provider ID, endpoint origin/path, model ID, and credential-present boolean, with the token and encrypted token material excluded. A separate explicit user action would be required to test a provider endpoint or transmit prompts.
