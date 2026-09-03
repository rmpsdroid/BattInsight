# BattInsight

BattInsight is an open-source Android battery diagnostics project focused on transparent,
testable analysis of battery and power behaviour.

[![Android CI](https://github.com/rmpsdroid/BattInsight/actions/workflows/android-ci.yml/badge.svg)](https://github.com/rmpsdroid/BattInsight/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%203.0--only-blue.svg)](LICENSE)

---

> ## ⚠️ Early development — not yet ready for daily use
>
> There is no release, no installable build, and **no diagnostic feature works yet**.
>
> What exists today is foundation and architecture work: the build chain, the package
> structure, the contracts that model how data will be acquired, and the tests that hold
> those contracts to measured platform behaviour. The application launches and shows a
> placeholder screen.
>
> **BattInsight is not currently a replacement for any existing battery statistics
> application.**

---

## Why this project

Android tells you *that* your battery drained. It is much harder to find out *what* drained
it — which wakelocks fired, which alarms woke the device, which app kept the CPU busy while
the screen was off.

Tools that answered this well have become unavailable: the widely used open-source option
was archived, and its successor is closed-source. BattInsight is an independent
reimplementation, built from direct measurement of what current Android actually permits an
application to observe.

The emphasis is on being **honest about what it can and cannot see**. A diagnostics tool
that silently shows an empty screen when it lacks access is worse than one that says so.

---

## Status

### Implemented

- Project foundation: Kotlin, modern Gradle/AGP build chain, CI, lint as a build gate
- Architectural contracts for data acquisition, backend identity and capability state
- A capability model that distinguishes *available*, *available but idle*, *available but
  incomplete*, *permission missing*, *not supported by this device*, *source unavailable*,
  *execution failed* and *unknown* — because collapsing those is what produces
  uninformative empty screens
- 39 unit tests covering the classification rules, written against platform output captured
  from real measurement

### Planned

None of the following exists yet.

| Area | Planned capability |
|---|---|
| Battery statistics | Aggregate collection and parsing |
| Wakelocks | Partial (per-application) wakelock attribution |
| Kernel wakelocks | Kernel wakelock attribution |
| Alarms | Alarm and scheduled-job attribution |
| Sessions | Charge/discharge session tracking that survives reboots and process death |
| Diagnostics | A redacted diagnostic bundle for troubleshooting |
| ADB-granted backend | Collection using permissions granted over ADB |
| Shizuku backend | Collection via a Shizuku shell session, needing no privileged app permissions |
| Reports and export | Structured, machine-readable export |

The roadmap deliberately puts correctness of session and snapshot handling ahead of
features and UI.

---

## Design principles

- **Local-first.** No telemetry, no analytics, no network stack. The application does not
  declare the `INTERNET` permission. See [PRIVACY.md](PRIVACY.md).
- **Capability is measured, not assumed.** Whether something works is established by
  attempting it and classifying the result, never inferred from a privilege level.
- **Multiple access backends behind one interface.** Measurement showed an ADB-granted app
  and a Shizuku shell session produce equivalent data, so both are treated as
  interchangeable implementations rather than one being a fallback.
- **Empty is not failure.** A source with nothing to report and a source that is absent are
  different states and must stay distinguishable.
- **Session correctness over cosmetics.** Historical data is never silently discarded.

---

## Requirements

| | |
|---|---|
| Planned minimum | Android 13 (API 33) |
| Language | Kotlin |
| JDK | 17 |
| Gradle | 9.7.1 (via the wrapper) |
| Android Gradle Plugin | 9.4.0 |
| compileSdk / targetSdk | 37 / 36 |

`compileSdk` and `targetSdk` differ deliberately: the project compiles against the newest
API surface but only opts into runtime behaviour that has been measured.

## Building

```bash
./gradlew assembleDebug      # build
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # lint (a build gate; abortOnError is enabled)
```

No signing configuration exists.

---

## Compatibility

**No broad compatibility claims are made.** Behaviour has so far been measured on an
Android 16 emulator and on a single Android 10 physical device, and Android 10 is below the
planned minimum. Behaviour on other Android versions, and on manufacturer software from
Samsung, OnePlus, OPPO and others, is **not yet verified on real hardware**.

Emulator results are not treated as evidence about physical devices.

---

## Documentation

| Document | Covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layering and why the boundaries sit where they do |
| [docs/capabilities.md](docs/capabilities.md) | The capability state model |
| [docs/data-sources.md](docs/data-sources.md) | Acquisition formats and prohibited commands |
| [docs/session-model.md](docs/session-model.md) | Charge/discharge session ownership (design) |
| [docs/security-privacy.md](docs/security-privacy.md) | Security and privacy posture in detail |
| [docs/provenance.md](docs/provenance.md) | Relationship to prior work |
| [docs/development.md](docs/development.md) | Build chain notes and conventions |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports and questions are welcome, though the
project is early enough that most features simply do not exist yet.

Security reports: see [SECURITY.md](SECURITY.md).

---

## Licence

**GNU General Public License v3.0 only** — see [LICENSE](LICENSE).

```
SPDX-License-Identifier: GPL-3.0-only
```

Version 3 **only**; no "or later" grant is offered.

## Relationship to prior work

BattInsight is **independent** and **not affiliated with, endorsed by, or supported by** the
authors of BetterBatteryStats or BBS Reloaded. No source from either has been copied, and
the closed-source successor was never decompiled. Full detail in [NOTICE.md](NOTICE.md).
