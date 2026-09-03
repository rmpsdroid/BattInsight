# NOTICE (DRAFT)

> **Status: DRAFT.** This file records provenance for a project that has not been released
> and whose final name has not been chosen. It becomes `NOTICE` when the project is
> published. It must be updated if the source position below ever changes.

---

## 1. This project's own licence

This project is licensed under the **GNU General Public License, version 3.0**.
The full text is in [`LICENSE`](LICENSE).

Copyright © 2026 the Battery Diagnostics contributors.
*("Battery Diagnostics" is a provisional working name — see §5.)*

---

## 2. Current source position — no third-party source is present

**As of Phase 2A, this codebase contains no source code copied from any other project.**

Specifically:

| Project | Status in this codebase |
|---|---|
| `asksven/BetterBatteryStats` (Apache-2.0) | **No source copied.** Reference material only |
| BBS Reloaded (`asksven/bbs_reloaded-releases`) | **No source, no assets, nothing.** Closed source — see §4 |
| Any other third-party source | None, beyond declared binary dependencies (§6) |

Every file in this repository was written for this project.

**This section must be rewritten the moment that stops being true.** Claiming Apache
attribution for code we have not copied would be as misleading as omitting it when we have.

---

## 3. Historical reference and inspiration

This project exists because BetterBatteryStats — a well-regarded Android battery
diagnostics application — was archived, and its successor was closed-sourced. It is an
independent reimplementation, informed by studying the archived project and by measuring
current Android platform behaviour directly.

**Reference project:**

| | |
|---|---|
| Name | BetterBatteryStats |
| Repository | `https://github.com/asksven/BetterBatteryStats` |
| Author | asksven (Sven Knispel) |
| Stated licence | Apache License 2.0 (asserted in the project README; the repository contains no `LICENSE` file) |
| Status | Archived; last pushed 2024-01-05 |
| **Audited commit** | **`ccb0904791ab20e35a187dbd2a4cf53643dcba19`** |

That commit SHA pins exactly which revision was read during the Phase 0 audit, so any
future provenance question can be answered against a specific tree rather than a moving
branch.

**What was taken from it:** an understanding of the problem domain — which battery
counters matter, which failure modes users actually report, and which architectural
decisions caused data loss. Ideas and observed behaviour are not copyrightable, and
re-implementing them independently is exactly what this project has done.

**What was not taken:** any line of code, any resource, any asset.

---

## 4. BBS Reloaded — explicitly excluded

The modern successor, distributed via `asksven/bbs_reloaded-releases`, is **closed-source
proprietary software**. Its author has stated this directly and explained the reasons.

**No BBS Reloaded code, resource, asset or decompiled artefact has been used, and none
will be.** The application was never decompiled, disassembled or unpacked. Its publicly
documented product behaviour — README, release notes, and user-filed issue reports — was
read as market research, which is the only legitimate use available.

---

## 5. Naming and branding

**This project uses a provisional working name.** "Battery Diagnostics" and the package
`com.rmpsdroid.batterydiagnostics` are placeholders chosen so the project can build; the
final brand and package identifier have not been decided.

The following are **not used and will not be used** as product branding:

- `BetterBatteryStats`, `Better Battery Stats` — explicitly excluded from the upstream
  Apache grant, which states the licence "does not apply to the use of the names".
- `BBS`, `BBS Reloaded`, `BetterBatteryStats Reloaded` — not named in that carve-out, but
  avoided as deliberate project policy: `BBS` is the upstream author's own shorthand and
  was that application's on-device label, and `BBS Reloaded` is a live proprietary product
  name. Reusing either would create exactly the confusion the upstream author objects to.
- The `com.asksven.*` package namespace.
- Any original BetterBatteryStats icon or artwork — also excluded from the upstream grant.

This project is **independent** and is **not affiliated with, endorsed by, or supported
by** the authors of BetterBatteryStats or BBS Reloaded.

---

## 6. Third-party dependencies

Binary dependencies resolved at build time. None is vendored into this repository.

| Dependency | Licence |
|---|---|
| Android Gradle Plugin, Android SDK | Android Software Development Kit Licence |
| AndroidX (`core-ktx`, `appcompat`, `activity-ktx`, `lifecycle-runtime-ktx`) | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache-2.0 |
| Kotlin standard library (supplied by AGP built-in Kotlin) | Apache-2.0 |
| JUnit 4 (test only) | Eclipse Public License 1.0 |

Apache-2.0 dependencies are compatible with GPL-3.0 as inbound licences.

---

## 7. If Apache-licensed source is ever imported

The reuse pathway remains open in principle but is **closed by default**: Phase 2A policy
is to write fresh code. Importing any file from the archived BetterBatteryStats would
require a separate, explicit decision, and would then oblige us to:

1. Confirm the file itself carries an Apache-2.0 header with an identifiable copyright
   holder. The repository README is **not** treated as resolving per-file provenance —
   fifteen files in that tree carry no header at all, and at least one names a different
   origin entirely.
2. Confirm its transitive dependencies are equally licence-clean.
3. Retain the licence header and copyright notice inside the imported file (Apache-2.0 §4(c)).
4. Mark the file as modified (§4(b)).
5. Ship the Apache-2.0 licence text alongside `LICENSE` (§4(a)).
6. Replace §2 of this file with an accurate statement of what was imported.

Note on §4(d): the upstream repository contains **no `NOTICE` file**, so no NOTICE
carry-forward obligation arises from it. This document exists as deliberate project
policy for transparency of provenance, not because a NOTICE was inherited.
