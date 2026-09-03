# Contributing to BattInsight

Thanks for looking. Please read this first, because the project is at an unusual stage.

## Current stage

BattInsight is **early foundation work**. The build chain, architecture contracts and tests
exist; the diagnostic features do not. Most things you might want to fix have not been
written yet.

That means:

- **Bug reports** about diagnostic features are premature — those features do not exist.
- **Build, tooling, documentation and correctness issues** are genuinely useful.
- **Large feature pull requests are likely to be declined** at this stage, not because they
  are unwelcome but because the layers they would sit on are still being established.

If you want to help, opening an issue to discuss first will save you effort.

## Ground rules that are not negotiable

These come from the project's design work and are enforced by tests.

1. **No source may be copied from BetterBatteryStats, BBS Reloaded, or any other project**
   without an explicit provenance decision recorded in `NOTICE.md`. BBS Reloaded is
   closed-source; nothing from it may be used under any circumstances.
2. **No state-changing battery statistics command.** `--reset`, `--reset-all`, `--write`,
   `--checkin` and the `enable`/`disable` options alter the user's data or device state. A
   test asserts none can appear in the code.
3. **`hidden_api_policy` is never modified.** It is a device-wide security downgrade
   affecting every installed application.
4. **No telemetry, analytics or crash reporting.** No `INTERNET` permission without a
   documented decision. See `PRIVACY.md`.
5. **Capability is measured, never inferred from privilege.** Do not conclude that
   something works because root or a permission is present. Attempt it and classify the
   result.
6. **Exit status is not a success criterion on its own.** Permission denials from Android
   arrive with exit status 0 and the error on stdout.
7. **Empty is not failure.** A source with nothing to report and a source that is absent
   are different states and must remain distinguishable.

## Before submitting

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug
```

All three must pass. Lint is a build gate (`abortOnError = true`).

- **Do not disable lint or tests to make a build pass.** Fix the cause. If lint reports a
  stale dependency, update the dependency rather than suppressing the check.
- **Do not add a lint baseline file.**
- **Do not add dependencies without saying why** in the pull request. There is deliberately
  no network stack and no dependency-injection framework.

## Style

- Kotlin, matching the surrounding code.
- **Comments should say why, not what.** Where a constant or a rule comes from a measured
  platform behaviour, record which observation produced it — that is why the existing code
  reads the way it does.
- Tests should assert distinctions that matter, so that collapsing them fails a test. Do
  not add tests purely to raise a coverage number.
- Create packages only when they hold real code.

## Licence

By contributing you agree that your contributions are licensed under **GPL-3.0-only**, the
same licence as the project.
