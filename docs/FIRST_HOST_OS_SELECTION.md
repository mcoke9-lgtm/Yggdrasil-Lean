# Initial Host Operating-System Decision

**Status:** Proposed selection for first installation; no OS installed and no hardware changes made.
**Target:** Intel NUC5i5RYH, currently reported by the user as having no operating system installed.
**Branch:** `independent-builder-runtime-foundation-001`

## Decision

**Proposed first choice: Debian 13 (Trixie), minimal amd64/server installation, without a desktop environment.**

Why:
- Debian 13 is the current stable release; Debian 13.7 was released 12 September 2026.
- Debian's published lifecycle provides three years of full support and two further years of LTS, currently through 30 June 2030.
- The NUC's Intel Core i5-5250U is a 64-bit x86 processor, so the amd64 installation path is appropriate.
- A minimal server installation avoids spending scarce memory and storage on a graphical desktop.
- The system can be administered remotely over the local network after setup, while keeping a local recovery path.

Official references:
- Debian 13 release and lifecycle: https://www.debian.org/releases/stable/
- Debian 13 amd64 installation guide: https://www.debian.org/releases/trixie/amd64/
- Debian installation images: https://www.debian.org/distrib/

## Alternative

Ubuntu Server 26.04 LTS is a reasonable alternative if the user prefers its installer or ecosystem. Canonical lists server installation requirements starting as low as 1.5 GB RAM and 4 GB storage, and standard support through May 2031.

Official references:
- https://documentation.ubuntu.com/release-notes/26.04/
- https://ubuntu.com/about/release-cycle

Do not install Ubuntu 26.10 merely because it is newer: it is an interim release with a much shorter support window than the 26.04 LTS.

## Hardware compatibility caution

A historical Notebookcheck review of the NUC5i5RYH reported serious graphics problems with several Linux desktop/live distributions of its era, involving Intel HD Graphics 6000. This is not proof that a current headless Debian server install will fail, but it is enough reason not to assume graphics compatibility.

Reference:
- https://www.notebookcheck.net/Intel-NUC-5i5RYH-Mini-PC-Review.138978.0.html

Therefore:
1. Prefer a minimal/headless server install.
2. Test current installer/live media before writing to internal storage.
3. Confirm the wired Ethernet adapter is detected.
4. If graphics display problems occur, try a text-mode installer or a serial/remote installation path only if the hardware supports it.
5. Do not proceed with disk partitioning until the target disk, capacity, and any existing data are confirmed.

## Installation readiness checklist

Before installation:
- Confirm exact NUC model from its case label.
- Inspect installed RAM.
- Inspect storage device and capacity.
- Confirm wired Ethernet and router availability.
- Obtain a USB flash drive and another computer to create installation media.
- Download the installer only from the official Debian site and verify its checksum/signature.
- Decide whether the internal disk may be erased; assume it will be erased by a clean install unless partitioning is deliberately changed.

## First boot configuration

Keep the first system deliberately small:
- security updates;
- SSH server with key-based authentication;
- firewall allowing only required local-network services;
- time synchronization;
- disk and memory health monitoring;
- user-controlled backup/export location.

Do not expose SSH or Builder APIs directly to the public internet. Remote access design comes later and must include authentication, authorization, and audit logging.

## Integrity status

- **Proposed:** Debian 13 minimal amd64/server.
- **Alternative:** Ubuntu Server 26.04 LTS.
- **Evidence checked:** current official release/lifecycle/installer documentation and historical device-specific Linux compatibility review.
- **Not tested:** installer boot, network detection, graphics, storage, thermal condition, or actual NUC configuration.
- **Not done:** no OS image downloaded, no USB created, no disk partitioned, and no installation started.
- **Decision authority:** user approves installation and any destructive disk operation.
