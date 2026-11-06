# First Host Assessment — Intel NUC5i5RYH

**Status:** Preliminary fit assessment from remembered device model and Intel's published specifications. The physical unit has not been inspected in this work session.
**Branch:** `independent-builder-runtime-foundation-001`

## 1. Known hardware

The user has previously identified access to an Intel NUC5i5RYH prototype.

Intel's specifications list:
- Intel Core i5-5250U, 2 cores / 4 threads, 1.60 GHz base and up to 2.70 GHz turbo.
- Maximum 16 GB DDR3L-1333/1600 memory across two SO-DIMM slots.
- Intel HD Graphics 6000 integrated graphics.
- M.2 and 2.5-inch storage support.
- Gigabit Ethernet and Wi-Fi.

Source: https://www.intel.com/content/www/us/en/products/sku/83255/intel-nuc-kit-nuc5i5ryh/specifications.html

## 2. Appropriate first role

**Proposed role: control-plane and continuity node.**

This NUC is a reasonable candidate to evaluate for:
- local Builder registry and role configuration;
- local API and message routing;
- append-only event journal and small database;
- permissions, audit records, health checks, and task coordination;
- backups and synchronization coordination;
- lightweight development and integration tests.

It should not be assumed to host nine substantial reasoning models concurrently. Its two-core mobile CPU and 16 GB maximum memory make that an unverified and likely constrained target. Larger inference jobs can later run on another user-controlled node through the same replaceable model interface.

## 3. What remains unknown about the physical unit

- Installed RAM (the maximum supported is not necessarily installed).
- Storage type, capacity, health, and free space.
- Installed operating system and version.
- Cooling condition, fan operation, and thermal throttling.
- Network connection reliability.
- Whether the machine can remain powered on when needed.

Do not install or migrate anything until these details and the user's backup preferences are clear.

## 4. Low-friction inventory checkpoint

When the NUC is available, collect only:
1. operating system and version;
2. installed RAM;
3. storage capacity and free space;
4. whether it is wired to the router or using Wi-Fi.

Then run a small benchmark before selecting a local language model. Keep the initial host role useful even if no language model can run acceptably on it.

## 5. Deployment rule

The first host is a starting point, not a permanent center of authority. Its records must be exportable, its service interfaces documented, and a second node must be able to take over defined responsibilities after an explicit handoff.

**Evidence status:** Intel's published model specifications checked; user's actual installed configuration and runtime performance remain unverified.
