# NOTICE

Provenance and third-party licence information for **BattInsight**.

---

## 1. Licence

BattInsight is licensed under the **GNU General Public License, version 3.0 only**.
The full text is in [`LICENSE`](LICENSE).

```
SPDX-License-Identifier: GPL-3.0-only
```

**Version 3 only.** No "or later" grant is offered. Moving to a future GPL version would
require a fresh decision by the project owner.

Copyright © 2026 the BattInsight contributors.

---

## 2. Independence

BattInsight is an **independent project**. It is **not affiliated with, endorsed by, or
supported by** the authors of BetterBatteryStats or BBS Reloaded.

The names *BetterBatteryStats*, *Better Battery Stats*, *BBS* and *BBS Reloaded*, the
`com.asksven.*` package namespace, and the original BetterBatteryStats icon and artwork are
**not used** by this project in any form.

---

## 3. Current source position

**BattInsight contains no source code copied from any other project.**

| Project | Status in this codebase |
|---|---|
| `asksven/BetterBatteryStats` (Apache-2.0) | **No source copied.** Studied as historical reference material only |
| BBS Reloaded (`asksven/bbs_reloaded-releases`) | **No source, no assets, nothing.** Closed source — see §5 |
| Any other third-party source | None, beyond the declared binary dependencies in §6 |

Every file in this repository was written for this project.

This section is updated if that ever changes — see §7.

---

## 4. Historical reference

BattInsight exists because BetterBatteryStats, a well-regarded Android battery diagnostics
application, was archived, and its successor was closed-sourced. BattInsight is an
independent reimplementation, informed by studying the archived project and by measuring
current Android platform behaviour directly.

| | |
|---|---|
| Name | BetterBatteryStats |
| Repository | `https://github.com/asksven/BetterBatteryStats` |
| Author | asksven (Sven Knispel) |
| Stated licence | Apache License 2.0 (asserted in that project's README; the repository contains no `LICENSE` file) |
| Status | Archived; last pushed 2024-01-05 |
| **Audited reference commit** | **`ccb0904791ab20e35a187dbd2a4cf53643dcba19`** |

That commit identifier pins exactly which revision was read, so any future provenance
question can be answered against a specific tree rather than a moving branch.

**What was taken:** an understanding of the problem domain — which battery counters matter,
which failure modes users report, and which design decisions caused data loss. Ideas and
observed behaviour are not copyrightable, and re-implementing them independently is what
this project has done.

**What was not taken:** any line of code, any resource, any asset.

---

## 5. BBS Reloaded

The successor application, distributed via `asksven/bbs_reloaded-releases`, is
**closed-source proprietary software**. Its author has stated this directly.

**No BBS Reloaded code, resource, asset or decompiled artefact has been used, and none will
be.** The application was never decompiled, disassembled or unpacked. Only its publicly
published documentation — README, release notes and user-filed issue reports — was read.

---

## 6. Third-party dependencies

Resolved at build time. None is vendored into this repository.

| Dependency | Licence |
|---|---|
| Android Gradle Plugin, Android SDK | Android Software Development Kit Licence |
| AndroidX (`core-ktx`, `appcompat`, `activity-ktx`, `lifecycle-runtime-ktx`) | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache-2.0 |
| Kotlin standard library (supplied by AGP built-in Kotlin) | Apache-2.0 |
| JUnit 4 (test only) | Eclipse Public License 1.0 |

Apache-2.0 dependencies are compatible with GPL-3.0-only as inbound licences.

---

## 7. If Apache-licensed source is ever imported

The reuse pathway is open in principle but **closed by default**: project policy is to write
fresh code. Importing any file from the archived BetterBatteryStats would require an
explicit decision, and would then oblige us to:

1. Confirm the file itself carries an Apache-2.0 header with an identifiable copyright
   holder. A repository-level README statement is **not** treated as resolving per-file
   provenance — fifteen files in that tree carry no header at all, and at least one names a
   different origin entirely.
2. Confirm its transitive dependencies are equally licence-clean.
3. Retain the licence header and copyright notice inside the imported file (Apache-2.0 §4(c)).
4. Mark the file as modified (§4(b)).
5. Ship the Apache-2.0 licence text alongside `LICENSE` (§4(a)).
6. Replace §3 of this file with an accurate statement of what was imported and from where.

On Apache-2.0 §4(d): the upstream repository contains **no `NOTICE` file**, so no NOTICE
carry-forward obligation arises from it. This document exists as deliberate project policy
for transparency of provenance, not because a NOTICE was inherited.
