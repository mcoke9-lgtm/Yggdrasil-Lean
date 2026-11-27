# Tracking Register 001

**Status:** Adopted architectural direction; this document is an implementation specification proposal.  
**Scope:** The Living Tree / YIM-001.  
**Separation:** The Tracking Register is distinct from the Human Understanding Register, assessment workflows, and principle/adoption records.

## Purpose

Allow the system to retain and monitor information without requiring an immediate interpretation, judgment, endorsement, or integration into the established architecture.

A tracked item is an observation about an external source or development. Recording it does not imply that its claims are true, that the user agrees with it, or that the item should influence project principles.

## Lifecycle

Items use these distinct states:

1. **Observed** — a source or development has been captured. No assessment is implied.
2. **Tracking** — the item is retained for future updates or comparison. No endorsement is implied.
3. **Assessed** — an explicit assessment has been recorded. Assessment does not imply adoption.
4. **Adopted** — an authorized decision has explicitly incorporated the item or a stated conclusion into a named project, principle, or architecture.

Lifecycle transitions are recorded as append-only events. The current state is derived from the event history; prior states and decisions are never overwritten.

Adopted requires an explicit authorization event identifying the authorizing actor, target project/principle, decision, timestamp, and supporting evidence. No automated observation, similarity match, or assessment may authorize adoption.

## Minimum record

Each tracked item should preserve:

- stable tracking_id
- current lifecycle state, derived from its event history
- original title or concise source label
- source URL or source identifier
- publisher/author when available
- publication timestamp when available
- capture timestamp
- verbatim user-provided note, if any
- optional topic/keyword labels
- optional links to related tracked items
- source-recovery status and retrieval notes

The system must distinguish publication time from capture time and must preserve unknown values as unknown rather than inventing them.

## Append-only event model

Each event should include:

- stable event_id
- tracking_id
- event type (observed, tracking_started, update_captured, assessment_recorded, adoption_authorized, or correction_appended)
- event timestamp
- actor/attribution
- source or evidence reference, when applicable
- source-recovery status (`unknown`, `available`, `partial`, or `unavailable`) and retrieval notes; each later retrieval attempt is a new event
- event payload
- prior-event digest or equivalent integrity link

Corrections append a new event and preserve the earlier record. They do not silently rewrite source content or history. Snapshot format version 2 adds source-recovery fields. Version 1 snapshots remain readable; legacy observations are exposed with recovery status `unknown`, and their original event payloads and hashes are preserved.

## Operating rules

- Capture first; do not force an opinion.
- Keep source material and system-generated summaries distinguishable.
- Do not infer user agreement from a link, a capture, a follow, or a tracking action.
- Do not infer truth from repetition or source count alone.
- Do not promote an item from Observed or Tracking to Assessed without a recorded assessment action.
- Do not promote an item to Adopted without explicit authorization.
- Keep tracked observations out of adopted principles, active requirements, and normative architecture by default.
- Updates may be appended while an item remains in Tracking; changed claims and source corrections must remain attributable to their source and time.
- If a source becomes unavailable, preserve the known reference and record the retrieval failure; do not treat unavailability as proof that the source never existed.
- A missing recovery check is `unknown`, not `unavailable`; later retrieval attempts append status events and never erase prior attempts.
- Tracking and notifications must be bounded and user-controllable. Do not create recurring schedules or external actions merely because an item was added.

## Relationship to other registers

- Tracking Register: what was observed and what has changed over time.
- Human Understanding Register: how stable technical identifiers are interpreted in human-readable terms.
- Assessment record: what was examined, by whom, using which evidence, and with what uncertainty.
- Adoption record: what was explicitly authorized for incorporation and where.

These records may link to one another, but they are not interchangeable. A tracking entry must not silently become a Human Understanding entry or an adopted principle.

## Initial example

The Ground News / POLITICO article about European attitudes toward the United States is an example of a tracked observation only:

- State: Tracking
- Assessment: none requested or recorded
- Adoption: none
- Agreement/endorsement: not inferred

## Verification requirements before implementation is called tested

At minimum, tests must establish:

1. New items begin as Observed.
2. Tracking can begin without assessment.
3. Source metadata distinguishes publication and capture times.
4. Repeated updates append events without overwriting earlier observations.
5. Assessment does not change the item to Adopted.
6. Adoption is rejected without an explicit authorization event.
7. Corrections preserve the original event and source attribution.
8. Unknown or unavailable source fields remain explicitly unknown; recovery outcomes and retrieval notes are appended as events.
9. Search returns lifecycle state and the matching item's event history; JSON export preserves the complete register snapshot and event chain.
10. Malformed snapshots fail with a register-specific validation error.
11. Existing Human Understanding Register data remains unchanged.

This document alone does not implement persistence, source monitoring, notifications, Android integration, or multi-writer synchronization. Those capabilities must be implemented and verified separately before they are described as available.
