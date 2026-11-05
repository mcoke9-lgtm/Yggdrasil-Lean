# ChatGPT–Yggdrasil Bridge 001

## Purpose
A local-first, user-directed Android share bridge between the Yggdrasil Nine Builder conversation and external apps such as ChatGPT. It does not modify ChatGPT's private interface, use private endpoints, or require an API key, server, or cloud service.

## Outbound: Yggdrasil to ChatGPT
1. Open **Nine Builder Conversation**.
2. Tap **Share thread with ChatGPT**.
3. Review and edit the proposed text. The first version prepares the current thread; it does not silently export it.
4. Confirm the separate disclosure prompt.
5. Choose ChatGPT (or another app) in Android's share sheet.

Android reports available share targets. Yggdrasil cannot guarantee ChatGPT will appear on every device or app version, or verify which recipient the user selects.

## Inbound: ChatGPT to Yggdrasil
1. In ChatGPT, use its Android share action for a response if that action offers plain-text sharing.
2. Choose **Yggdrasil Bridge** from Android's share targets.
3. Review the incoming text and explicitly confirm **Review and add**.
4. The text is appended as a user-confirmed contribution to the currently active Nine Builder thread.

The bridge accepts only `ACTION_SEND` with `text/plain`, rejects empty content and content over 8,000 characters, previews before import, and never treats the sending app's identity as authenticated. If ChatGPT does not expose a compatible share action, the return path is unavailable in that app version; no hidden integration is assumed.

## Provenance and storage
The append-only local Builder event journal records event ID, direction, source/destination labels, thread and turn linkage, timestamp, character count, SHA-256 digest, explicit consent, processing state, and whether sender identity was verified. The event envelope does **not** store the message body. The actual imported response is stored in the ordinary Nine Builder conversation journal. Outbound text is handed to Android's share sheet only after review and approval. No Yggdrasil server or OpenAI API call is used.

Inbound audit uses pending and committed events around the conversation append. These are two local journals, not one atomic database transaction; a pending-only event or incomplete audit is surfaced rather than represented as completed. The hash chain is tamper-evident, not an authenticated signature or proof of sender identity.

## Boundaries
- No ChatGPT private UI modification, scraping, hidden API, or automatic access to ChatGPT conversation history.
- No automatic background synchronization or silent import/export.
- No API credential, billing, cloud provisioning, or external service required.
- No change to recovery restore/export logic. Bridge provenance uses the existing local continuity event journal; the recovery backup's separate operational-provenance capture is not automatically extended by this bridge.
- The Nine Builders remain coordinated roles in the existing runtime, not nine independent model processes.
