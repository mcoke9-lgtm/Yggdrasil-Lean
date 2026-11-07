# SCH-001 — NUC physical readiness checkpoint

**Status:** Read-only evidence review complete. Physical readiness remains **NOT VERIFIED**.
**Target:** Intel NUC5i5RYH
**Purpose:** Determine whether the existing NUC can host a separate SCH-001 control-plane / recovery-witness candidate before any provider is selected.

## Evidence reviewed

1. The repository's `docs/NUC5i5RYH_HOST_ASSESSMENT.md` records the model and Intel's published specifications: Core i5-5250U, two cores/four threads, up to 16 GB DDR3L memory, M.2 and 2.5-inch storage support, Gigabit Ethernet and Wi-Fi. It explicitly says the physical unit was not inspected in that work session.
2. The repository's `docs/FIRST_HOST_OS_SELECTION.md` records the unit as previously reported by the user to have no operating system installed. Debian 13 minimal amd64 is a proposal only. It explicitly says no image was downloaded, USB created, disk partitioned, or installation started.
3. No actual installed RAM, disk model/capacity/health, firmware/BIOS state, wired network detection, thermal condition, power/uptime, or boot test is evidenced by the repository.
4. A read-only search of connected Dropbox for `Intel NUC5i5RYH NUC host Debian hardware` returned no results.
5. The personal project-history retrieval likewise found no later physical inspection or setup facts.

## Current readiness assessment

| Readiness dimension | Evidence | Status |
|---|---|---|
| Exact model | Model recorded in project assessment; no current case-label/photo verification | PARTIAL |
| CPU architecture | Intel model specifications indicate x86-64 capability | DOCUMENTED, NOT DEVICE-TESTED |
| Installed RAM | Unknown; 16 GB is model maximum, not confirmed installed | UNKNOWN |
| Storage device, capacity, health and free space | Unknown | UNKNOWN |
| Operating system | Last user-reported state: no OS installed; no later install evidence | REPORTED EMPTY, NOT RECHECKED |
| Firmware/BIOS and boot media | Not inspected | UNKNOWN |
| Wired Ethernet/router | Model supports Gigabit Ethernet; actual link and driver untested | UNKNOWN |
| Cooling/fan/thermal stability | Not inspected | UNKNOWN |
| Power and unattended uptime | Not tested | UNKNOWN |
| Backup destination and restore path | Not selected or tested | UNKNOWN |
| Independent custody from scheduler and its backups | Not designed/verified operationally | NOT READY AS WITNESS |
| Conditional append, durable receipt and rollback continuity | No witness service installed or tested | NOT PRESENT |

## Decision

**Do not select or deploy a witness provider yet.** The NUC remains a promising possible host, but no evidence supports calling it physically ready. The previously reported absence of an OS means installation is a separate future step, not an assumed capability.

The first low-risk inspection, when the unit is physically accessible, is non-destructive:
- verify the case label and model;
- inspect firmware/BIOS inventory for installed RAM and detected storage;
- record storage model/capacity and health where firmware or a trusted live environment permits;
- check fan operation, physical condition and power adapter;
- test wired Ethernet link and router assignment.

Do not partition, format, install, update firmware, or change boot settings during this inventory. Any installation or destructive operation requires a separate user decision and confirmed backup/data-erasure understanding.

## Minimal evidence needed to resume

Provide a photo of the NUC case label and firmware/BIOS information screens, or report:
- installed RAM;
- detected storage model and capacity;
- whether an operating system is installed;
- whether it powers on and reaches firmware setup;
- whether wired Ethernet links to the router;
- whether fan/cooling appears normal.

If the unit is already running an OS, its OS/version, free storage and disk-health output can be checked later. Do not send passwords, recovery keys, API tokens, serial numbers, or other secrets.

## Integrity and scope

- **Observed:** repository documents and their explicit evidence limitations; no Dropbox search result.
- **Previously reported:** no OS installed.
- **Inferred:** NUC may be a suitable small control-plane host if it passes physical and operational checks.
- **Unknown:** actual current hardware condition and availability.
- **Not done:** no device connection, remote probe, installation, provisioning, firmware change, disk write, service setup, credential access, or provider selection.
- **Status:** proposed host only; not an operational scheduler, witness, or failover node.
