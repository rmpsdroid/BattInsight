# Battery Diagnostics

> ## ⚠️ PRE-RELEASE DEVELOPMENT PROJECT
>
> This is not a released application. There is no build to install and no feature works yet.
>
> **"Battery Diagnostics" is a temporary project name.** The final brand, the public
> repository name and the package identifier `com.rmpsdroid.batterydiagnostics` are all
> **provisional placeholders** chosen so the project can compile. None has been decided.

An Android battery diagnostics application: what drained your battery between charges, and
why. Independent reimplementation, currently at the foundation stage.

---

## Status

**Phase 2A — project foundation.** The build chain, package structure, architectural
contracts, tests, lint gate and documentation exist. No product feature does.

| | |
|---|---|
| Builds | ✅ `assembleDebug` |
| Tests | ✅ 24 unit tests |
| Lint | ✅ passes with `abortOnError = true` |
| Installable | Yes, but shows only a placeholder screen |
| Useful | Not yet |

### Deliberately not implemented yet

Battery statistics collection · checkin parser · protobuf decoder · Shizuku integration ·
permission granting · root support · persistence schema · charging state machine · battery
sessions · wakelock UI · charts · exports · notifications · widgets · battery health.

---

## What has been established

Four investigation phases preceded this code. Their measured findings — not assumptions —
shape the contracts in `app/src/main/java/.../`:

- **Battery statistics are reachable two ways**, and both were measured producing
  structurally identical output: an ordinary app granted three permissions, or a Shizuku
  shell session needing none of them.
- **The minimum permission set is three, not four.** `DUMP`, `PACKAGE_USAGE_STATS` and
  `INTERACT_ACROSS_USERS`. `BATTERY_STATS` was measured **unnecessary** despite both
  predecessor applications requesting it.
- **Kernel wakelocks work without root or debugfs**, via battery statistics rather than
  the `/sys` and `/proc` paths the archived predecessor used, which were unreadable on
  every environment tested.
- **Every failure mode returns exit status 0.** Permission denials arrive on stdout;
  `UsageStatsManager` returns an empty list rather than throwing. Classification is by
  content, never by exit code — this is why `CapabilityState` has eight cases.

Full detail lives in the phase reports outside this repository.

---

## Requirements

| | |
|---|---|
| JDK | 17 |
| Gradle | 9.7.1 (via wrapper — do not install separately) |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | supplied by AGP built-in Kotlin |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 33 (Android 13) |

`compileSdk` and `targetSdk` differ deliberately: we compile against the newest API surface
but only opt into runtime behaviour we have measured. See `app/build.gradle.kts`.

## Building

```bash
./gradlew assembleDebug      # build
./gradlew testDebugUnitTest  # unit tests
./gradlew lintDebug          # lint (a real gate; abortOnError is on)
```

No signing configuration exists and none will use an inherited key.

---

## Documentation

| Document | Covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Layering and why the boundaries sit where they do |
| [docs/capabilities.md](docs/capabilities.md) | The capability state model and why eight states |
| [docs/data-sources.md](docs/data-sources.md) | Acquisition formats, prohibited commands, UID name resolution |
| [docs/session-model.md](docs/session-model.md) | Charging and session ownership (design only) |
| [docs/security-privacy.md](docs/security-privacy.md) | Local-first defaults and what is not declared |
| [docs/provenance.md](docs/provenance.md) | Relationship to prior work |
| [docs/development.md](docs/development.md) | Build chain notes and conventions |
| [NOTICE-DRAFT.md](NOTICE-DRAFT.md) | Provenance and third-party licences |

---

## Licence

**GNU General Public License v3.0** — see [LICENSE](LICENSE).

Copyleft is a deliberate choice. The archived predecessor was permissively licensed, and
its author closed the successor's source specifically because forks reused its name and
artwork. GPL-3.0 requires derivative works to remain open, and still accepts Apache-2.0
code inbound should we ever import any.

## Relationship to prior work

This project is **independent** and **not affiliated with, endorsed by, or supported by**
the authors of BetterBatteryStats or BBS Reloaded.

No source from either has been copied. BBS Reloaded is closed-source and was never
decompiled. See [NOTICE-DRAFT.md](NOTICE-DRAFT.md).

## Compatibility

Only Android 16 (emulator) and Android 10 (one physical device) have been measured, and
Android 10 is below the supported floor. **No claim is made about any other Android
version or manufacturer** until measured on real hardware.
