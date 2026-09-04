# Development

## Build chain

| Component | Version | Note |
|---|---|---|
| JDK | 17 | Required by AGP 9.4.0 |
| Gradle | 9.7.1 | Via wrapper. AGP 9.4.0 requires 9.6.0 minimum |
| Android Gradle Plugin | 9.4.0 | Current stable, September 2026 |
| Kotlin | Supplied by AGP | See below |
| compileSdk | 37 | API surface compiled against |
| targetSdk | 36 | Runtime behaviour opted into |
| minSdk | 33 | Android 13 |

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## AGP 9.x has built-in Kotlin

**Do not apply `org.jetbrains.kotlin.android`.** AGP 9.0+ supplies Kotlin itself and
rejects the plugin outright:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

There is also no `kotlinOptions` block in AGP 9.4; it was removed. Kotlin's `jvmTarget`
follows `compileOptions`. The version catalog therefore has no Kotlin plugin entry.

## compileSdk 37 with targetSdk 36

Deliberate, and the two are independent:

- **compileSdk 37** is the API surface we compile against. Required by androidx.core 1.19.0.
  Raising it does not change runtime behaviour.
- **targetSdk 36** is the runtime behaviour we opt into. Android 16 is the platform every
  measurement was taken on. We do not opt into Android 17 behaviour we have not measured.

Lint's `OldTargetApi` warning is expected and deliberate. Raise `targetSdk` when Android 17
has been measured on real hardware, not before.

## Lint is a gate

`abortOnError = true`. Do not set it false and do not suppress broad categories. If lint
reports a stale dependency, update the dependency rather than the suppression list. That is
how the AndroidX versions here were chosen.

Report format flags such as `htmlReport` are deprecated in AGP 9.x; reports are always
generated.

## Conventions

- Contracts carry the measurement that justifies them in a comment. If a constant looks
  arbitrary, the comment should say which observation produced it.
- Tests assert distinctions that measurements proved necessary, so collapsing a state fails
  a test rather than passing silently. Do not add coverage-padding tests.
- Packages are created only when they hold real code.
- No dependency is added without a stated reason. There is no network stack.

## UI toolkit: Compose

Compose is used for the Capability Centre and onwards.

The Capability Centre is the first genuinely state-driven screen and the template for every
later diagnostic screen, so adopting Compose on one screen with no product UI was the
cheapest possible moment. Two defects in the predecessor's tracker were clipped text on an
ordinary phone and a broken title at large font scale, both caused by layouts assuming a
width and a text size — the class of problem Compose handles better by default. Deferring
would have meant rewriting whatever Views UI accumulated in the meantime.

### Version coupling to watch

AGP supplies Kotlin, but Compose still needs a separate compiler plugin whose version must
be compatible with it:

```
alias(libs.plugins.kotlin.compose)   // org.jetbrains.kotlin.plugin.compose
```

This is the one place where an AGP upgrade can require a matching change elsewhere. If a
build fails after bumping AGP with a Compose or Kotlin version error, this plugin is the
first thing to check. Verify a bump with a genuine recompile: Gradle will report
`FROM-CACHE` and appear to succeed without having compiled anything.

## Not yet decided

The routine acquisition format (protobuf versus checkin), dependency injection, and the Room
schema.
