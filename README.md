# BattInsight

BattInsight is an open-source Android battery diagnostics project focused on transparent,
testable analysis of battery and power behaviour.

[![Android CI](https://github.com/rmpsdroid/BattInsight/actions/workflows/android-ci.yml/badge.svg)](https://github.com/rmpsdroid/BattInsight/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%203.0--only-blue.svg)](LICENSE)

---

> ## ⚠️ Early development — not yet ready for daily use
>
> There is no release, and **no battery diagnostic feature works yet**.
>
> What exists today is the capability architecture, the access setup that feeds it, and the
> session engine that decides what an observation means over time. The application can
> determine which access backends are usable, guide you through granting access by whichever
> of three routes you prefer, verify that battery statistics can actually be acquired, and
> track charge and discharge intervals correctly across reboots, process death and clock
> changes, storing them so they survive the app closing. It decodes kernel and app wakelock
> counters from `dumpsys batterystats`, stores a verified subset of them, and shows what
> changed during a battery period — refusing to show a difference when the readings cannot
> honestly be compared. It does not chart anything yet, and it decodes four of the roughly
> forty-six record types Android emits.
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
- **Runtime capability detection** — what the app can actually do is established by
  attempting operations and classifying the result, never inferred from an installed
  package or a permission flag
- **Granted-app backend inspection** — per-permission state for the three permissions the
  platform actually requires, and a behavioural check that acquisition works
- **Shizuku availability and identity detection** — installed, running and authorised are
  three separate states, and the execution identity is measured rather than assumed
- **Capability Centre** — a screen showing each backend, permission and capability with a
  specific reason for its state, plus the preferred and active backend and why
- **Access setup** — onboarding that offers three routes and lets you choose: live Shizuku,
  a one-time Shizuku-assisted grant, or three ADB commands run from a computer. g is
  granted without an explicit confirmation naming exactly what will change
- **Access removal** — the three permissions can be removed again from inside the app when
  Shizuku is available, or with the exact ADB commands when it is not
- **Battery session engine** — charge and discharge intervals with stable identity,
  measured on the monotonic clock so a clock or timezone change cannot alter a duration,
  with reboots, process death and missed transitions each handled distinctly and honestly
- **Snapshot comparability** — two readings are only compared when it is valid to do so, and
  a refusal always explains itself rather than showing a blank
- A capability model that distinguishes *available*, *available but idle*, *available but
  incomplete*, *permission missing*, *not supported by this device*, *source unavailable*,
  *execution failed* and *unknown* — because collapsing those is what produces
  uninformative empty screens
- 258 unit tests and 35 instrumented tests, written against platform output captured
  from real measurement and validated on an Android 16 emulator

**Battery diagnostics are partially implemented.** The application tracks and stores charge
and discharge periods, decodes kernel and app wakelock counters from `dumpsys batterystats`,
and presents what accumulated during a period through history and detail screens.

Two limits are worth stating plainly. It decodes **four** of the roughly forty-six aggregate
record types Android emits — everything else is counted and reported as undecoded rather than
guessed at. And it shows no charts: Phase 9 defines those once this presentation is stable.

### Planned

None of the following exists yet.

| Area | Planned capability |
|---|---|
| Battery statistics | Aggregate collection and parsing (acquisition is verified; no parser yet) |
| Wakelocks | Partial (per-application) wakelock attribution |
| Kernel wakelocks | Kernel wakelock attribution |
| Alarms | Alarm and scheduled-job attribution |
| Charts | Trends over time (history and detail screens exist; nothing charts them yet) |
| Diagnostics | A redacted diagnostic bundle for troubleshooting |
| Reports and export | Structured, machine-readable export |

The roadmap deliberately puts correctness of session and snapshot handling ahead of
features and UI.

---

## Access methods

Battery statistics need privileged access that Android does not offer through an ordinary
permission prompt. BattInsight supports three routes and asks you to choose; it does not
choose for you, because they differ in what the application ends up holding.

| Route | What BattInsight holds | Needs Shizuku running | Notes |
|---|---|---|---|
| **Shizuku (recommended)** | None of the 3 elevated Android permissions | Yes | Measured faster, and resolves application names the app UID cannot. Shizuku usually needs starting again after a reboot |
| **Independent access** | `DUMP`, `PACKAGE_USAGE_STATS`, `INTERACT_ACROSS_USERS` until removed | No | Granted once, with Shizuku's help, then works on its own |
| **ADB commands** | The same three permissions | No | Three commands run from a computer; no extra app required |

You can also continue without setup. Detailed diagnostics are unavailable in that mode and
the application says so rather than showing an empty screen.

[Shizuku](https://shizuku.rikka.app/) is a separate, independent open-source project.
BattInsight does not bundle, download or install it, and is not affiliated with it.

`BATTERY_STATS` is deliberately **not** requested: measurement showed acquisition succeeds
with it denied, even though comparable applications ask for it.

---

## Design principles

- **Local-first.** No telemetry, no analytics, no network stack. The application does not
  declare the `INTERNET` permission. See [PRIVACY.md](PRIVACY.md).
- **Capability is measured, not assumed.** Whether something works is established by
  attempting it and classifying the result, never inferred from a privilege level.
- **Multiple access backends behind one interface.** Measurement showed an ADB-granted app
  and a Shizuku shell session produce equivalent data, so both are treated as
  interchangeable implementations rather than one being a fallback.
- **The user chooses the security posture.** The two working routes differ in whether
  BattInsight itself ends up holding elevated permissions, so it never switches between
  them silently. A working alternative is offered, never applied.
- **Flags are evidence; acquisition is proof.** Setup is reported as ready only after
  battery statistics have actually been read, never because three permissions look granted.
- **Empty is not failure.** A source with nothing to report and a source that is absent are
  different states and must stay distinguishable.
- **Monotonic time for durations, wall clock for display.** Changing the clock or crossing a
  timezone must never alter a measured interval.
- **Inferred is not observed.** A transition reconstructed at start-up is labelled as such,
  because the application did not see it happen.
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
| [docs/persistence.md](docs/persistence.md) | What is stored, how it survives, and what is deliberately not stored |
| [docs/batterystats-decoding.md](docs/batterystats-decoding.md) | The source format decision, what is decoded, and counter units |
| [docs/ui-navigation.md](docs/ui-navigation.md) | Screens, the history query boundary, and what the wording is not allowed to claim |
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
