# SCH-001 — Cloud and Android candidate reassessment

**Status:** proposed architecture reassessment, based on the existing repository inventory and current public Cloudflare D1 documentation. No cloud account, database, Worker, API token, phone setting, APK, or service was created or changed.

## Executive decision

Prioritize a **free-cloud transactional witness prototype** and the existing Android application as an authenticated operator/client. Put the Intel NUC on hold. Do not treat the phone's local database or background scheduler as the independent monotonic witness.

Cloudflare D1 is a credible first candidate for a low-cost *prototype*, not an adopted production witness. Its transactional batch behavior and single-threaded per-database execution are promising for conditional append. Its restore capability, account-level administration, free-tier exhaustion behavior, and lack of demonstrated append-only/rollback-continuity guarantees mean it does not yet pass SCH-001 acceptance gates.

## Existing Android evidence

The prior infrastructure inventory records:
- Android scheduler prototype with local SQLite task/run storage and WorkManager.
- Nine Builders Android path with a dedicated thread, Android Keystore AES-GCM prompt encryption, explicit approval, cycle checkpoints and Stop notification.
- No verified APK installation on the user's Galaxy S21, device-level execution test, or unattended overnight run.
- WorkManager is inexact and must not be treated as hard-real-time scheduling or an always-on witness.
- Local SQLite and Keystore do not create a remote monotonic authority record.

The project notes identify a Galaxy S21, but the exact physical handset/model and present installation state have not been independently confirmed in this reassessment.

**Recommended Android role:** authenticated operator interface, witness status reader, approval surface, and recovery/reconciliation client. It may also run supporting scheduler work subject to Android lifecycle constraints. Do not make local SQLite the authority source.

## Cloudflare D1 current documented properties

Current official documentation reviewed on 2026-11-06 states:
- D1 is available on Workers Free and Paid plans.
- Free limits include 10 databases per account, 500 MB per database, 5 GB total account storage, 5 million rows read/day, and 100,000 rows written/day.
- Free-plan Time Travel recovery is limited to seven days; Paid is 30 days.
- Exceeding daily read/write limits prevents queries until the limit resets; reaching included storage limits prevents further inserts and certain schema changes until capacity is freed or plan changes.
- Each D1 database is single-threaded and processes queries one at a time.
- D1 `batch()` runs its statements in one SQL transaction; if one statement fails, all statements in that batch roll back.
- Time Travel can restore a database to an earlier point within the plan's retention window.

Sources:
- D1 overview: https://developers.cloudflare.com/d1/
- D1 limits: https://developers.cloudflare.com/d1/platform/limits/
- D1 pricing and free-tier behavior: https://developers.cloudflare.com/d1/platform/pricing/
- D1 FAQ/concurrency: https://developers.cloudflare.com/d1/reference/faq/
- D1 batch transaction behavior: https://developers.cloudflare.com/d1/observability/find-expensive-queries/
- D1 Time Travel: https://developers.cloudflare.com/d1/reference/time-travel/

## SCH-001 fit assessment

| Gate | D1 assessment | Status |
|---|---|---|
| Transactional conditional append | Single-threaded database plus transactional batch is a promising foundation. Must implement expected-sequence/generation conditional update and test concurrent clients, stale writers and unique event IDs. | CANDIDATE / NOT TESTED |
| Same-ID idempotent retry | Can be modeled with unique event/transfer IDs and stored receipts. Must prove identical retries return the original receipt and altered payloads are rejected. | DESIGN NEEDED |
| Durable acknowledgement | Provider documents durable writes, but SCH-001 must verify the precise acknowledgement contract and ambiguous-timeout behavior. | NOT VERIFIED |
| Append-only history | SQL tables are not inherently append-only; application and database access controls must deny ordinary update/delete of accepted events. Privileged administrators remain a concern. | NOT ESTABLISHED |
| Independent administration | Separate from scheduler host, but Cloudflare account owner/admin control is a shared trust boundary for database, Worker and restore. | PARTIAL |
| Rollback continuity | Time Travel intentionally permits restoring an older state. That is useful disaster recovery but does not prove monotonicity. An independent checkpoint/receipt history outside the D1 restore domain is required. | FAIL / OPEN |
| Failure visibility | Free-tier query suspension is detectable as an error but removes witness availability. All authority changes and destination activation must fail closed. | DESIGN REQUIRED |
| External fencing | D1 does not enforce stale-generation rejection at email, payment, repository, or other side-effect systems. | NOT PRESENT |

## Proposed topology

- **Existing Nine Builders runtime:** remains the scheduler/executor; reuse its task/run ledger, encrypted vault, approval and safe-resume behavior.
- **Cloud witness candidate:** narrow authority-event ledger only; no task execution or general memory role.
- **Android handset:** operator approval, read-only status, authenticated recovery client, and notification surface. The exact handset model must be confirmed before device-specific installation guidance.
- **Independent continuity evidence:** export/receipt/checkpoint path outside the D1 account's ordinary restore authority. This must be designed and tested; GitHub history alone is not accepted as the production witness.
- **NUC:** deferred; no OS installation or hardware preparation while cloud/Android candidate path is evaluated.

## Required prototype tests before any adoption

1. Two independent clients submit competing appends with the same expected sequence and generation; exactly one succeeds.
2. Stale sequence, stale generation, wrong owner and wrong task fingerprint are rejected.
3. Same event ID and identical payload returns the original receipt; same ID with changed payload is rejected.
4. Simulate a committed write whose response is lost; resolve by querying the same event ID without creating a second event.
5. Force one statement in a D1 batch to fail; verify no partial event/receipt commit.
6. Restore D1 to an older Time Travel point; verify the independent checkpoint detects the rollback and blocks authority changes.
7. Exhaust or simulate free-tier unavailability; verify no authority transfer or destination activation.
8. Attempt unauthorized update/delete of accepted events; verify ordinary application identities cannot alter history.
9. Verify Worker/API authentication, secret handling, least privilege, account recovery, export, and independent custody.
10. Test Android process death, reboot, Doze/background restrictions, offline operation, duplicate taps, and stale cached authority. The phone must display uncertainty and never authorize offline transfer.

## Decision and boundaries

**Proposed:** prioritize Cloudflare D1 as a free-tier prototype candidate; use Android as operator/client; reuse Nine Builders runtime; defer NUC.

**Not selected/adopted:** Cloudflare as production witness, any account or plan, or the exact handset.

**Not implemented:** D1 schema, Worker/API, database, credentials, APK installation, Android settings, append-only enforcement, external continuity anchor, fencing, automatic failover or task activation.

No credentials should be shared in chat. Any later account setup must be performed by the user through the provider's own secure interface. No paid upgrade is assumed.
