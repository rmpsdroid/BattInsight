# Provenance

`SPDX-License-Identifier: GPL-3.0-only`

Full detail is in [`../NOTICE-DRAFT.md`](../NOTICE-DRAFT.md). This is the short version.

## Independent

This project is **not affiliated with, endorsed by, or supported by** the authors of
BetterBatteryStats or BBS Reloaded.

## No copied source

**As of Phase 2A this codebase contains no source copied from any other project.** Every
file was written for this project.

The archived `asksven/BetterBatteryStats` (Apache-2.0, audited at commit
`ccb0904791ab20e35a187dbd2a4cf53643dcba19`) is **reference material only**, read to
understand the problem domain and the failure modes users reported.

BBS Reloaded is **closed-source proprietary software**. No code or asset from it was used
and it was never decompiled. Its public README, release notes and user-filed issues were
read as market research, the only legitimate use available.

## Reuse is closed by default

The default is **write fresh code**. Importing any Apache-licensed file would require a
separate explicit decision plus the compliance steps in `NOTICE-DRAFT.md` section 7.

Two findings keep the bar high:

- Fifteen files in the upstream tree carry **no licence header at all**, and at least one
  names an external origin. A repository-level README statement does not resolve per-file
  provenance.
- The one file previously considered a strong candidate, the UID name resolver, addresses a
  problem that turned out to be a modern package-visibility question rather than the
  formatting helper it appeared to be. Copying it would not solve the measured defect.

## Naming

**"Battery Diagnostics" is a provisional working name**, as is
`com.rmpsdroid.batterydiagnostics`. Neither is final.

Not used as branding: `BetterBatteryStats` and `Better Battery Stats` (explicitly excluded
from the upstream Apache grant), the original icon and artwork (also excluded), and, as
deliberate project policy rather than licence obligation, `BBS`, `BBS Reloaded` and the
`com.asksven.*` namespace.
