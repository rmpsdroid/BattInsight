# Privacy

BattInsight is a battery diagnostics tool. Tools of this kind read information about what a
device has been doing — which applications ran, which held wakelocks, which used the
network. That information is sensitive, so the project's position on it is stated plainly
here rather than buried.

[`docs/security-privacy.md`](docs/security-privacy.md) covers the same ground in
engineering detail; this page is the short, human-readable version.

---

## Current behaviour

BattInsight is **pre-release**. As of the current source:

| | |
|---|---|
| **Telemetry** | None. No usage reporting of any kind |
| **Analytics** | None. No analytics SDK is present |
| **Crash reporting** | None. No crash reporting service is present |
| **Advertising** | None |
| **Network access** | **The application does not declare the `INTERNET` permission.** It cannot make network requests |
| **Remote data collection** | None. No data is transmitted anywhere |
| **Accounts / sign-in** | None |
| **Cloud backup** | Disabled. `allowBackup="false"`, and backup rules exclude every data domain |
| **Stored on device** | Your access-method choice, and your own battery sessions. See below |

An application that cannot reach the network is the strongest privacy statement available
to a tool that reads usage history, and that is the current design.

## Data storage

BattInsight stores two things in its own private storage, and nothing else:

1. **Your access-method choice** — a single string.
2. **Your battery sessions** — the charge and discharge intervals it has observed, and the
   battery readings that mark their boundaries: level, temperature, voltage, charge counter,
   plug source and health, each with the time it was taken.

The second is new. Sessions used to be forgotten when the application closed, which made the
interval you were shown true but disposable. They are now kept so that history can eventually
be charted — which is the point of a battery diagnostics tool.

What is stored is BattInsight's own reading of the *public* battery broadcast every
application on Android can see. It is not per-application usage, and it names no packages.

**Capability and permission state is still never stored.** Those readings describe the device
*at a moment* and go stale as soon as anything changes; keeping them would create a second,
quieter source of truth able to disagree with reality. Readiness is therefore always
re-checked, never remembered — which is also why there is no "setup complete" flag that could
keep claiming access you no longer have.

Two identifiers are stored alongside each reading, and both are worth naming plainly. The
first is a UUID BattInsight generates for each session, which means nothing outside this
application. The second is the kernel boot identifier, a value that changes on every restart;
it is what lets BattInsight tell a reboot from a clock change, and it never leaves the device.

Nothing is deleted on your behalf. If a future version cannot read what an earlier one wrote,
it says so and leaves the data alone — it does not start fresh by wiping it. See
[docs/persistence.md](docs/persistence.md).

Everything the application stores, now and in future, stays **on the device only**, in the
application's own private storage, and leaves the device only in a file you explicitly choose
to create and share.

Cloud backup is disabled deliberately. Beyond privacy, restoring another device's battery
snapshots would corrupt session identity, because boot identifiers and counters would not
match the device they were restored onto.

## Diagnostic bundles

A redacted diagnostic bundle is **planned but not implemented**. When it exists:

- it will be generated only on explicit user action;
- its contents will be shown before it is shared;
- it will be redacted by default — no installation identifiers, no account data, no
  location;
- sharing it anywhere is entirely the user's choice.

Until then, no such feature exists.

## Permissions

The application declares exactly three, and **holds none of them unless you grant them**:

| Permission | What it allows |
|---|---|
| `android.permission.DUMP` | Read Android's own diagnostic reports, including the battery statistics service |
| `android.permission.PACKAGE_USAGE_STATS` | See how long applications have been used, which the battery statistics path requires |
| `android.permission.INTERACT_ACROSS_USERS` | Required by the battery statistics service itself, which asks across user profiles even on a single-profile device |

Declaring a permission is not holding it. None of these can be granted by an in-app prompt;
they arrive only through ADB or a Shizuku session, and only when you ask for that.

### You choose how much BattInsight holds

There are two working arrangements, and they differ in what this application ends up with:

- **Shizuku (recommended)** — BattInsight holds **none** of the three. The privileged work
  happens in a process Shizuku owns, for as long as Shizuku is running.
- **Independent access** — BattInsight holds all three, until you remove them. In exchange
  it works without Shizuku running.

BattInsight never switches between these by itself. If your chosen route stops working it
says so and offers the alternative; taking it is your decision.

You can remove the three permissions from inside the application whenever Shizuku is
available, and it shows you the exact ADB commands when it is not.

### What is deliberately not requested

- `BATTERY_STATS` — **measured to be unnecessary.** Acquisition succeeded with it denied.
  Comparable applications ask for it; this one does not.
- `INTERACT_ACROSS_USERS_FULL` — named by Android in its own refusal message, but not
  grantable to an ordinary application, so asking would waste your time.
- `INTERNET` — nothing in the design needs it.
- `QUERY_ALL_PACKAGES` — sensitive under app-store policy, and measurement showed it is not
  needed to detect Shizuku.

### App-operations are not modified

Android keeps a separate "app-op" for usage access. BattInsight **reads** it and never
changes it: measurement showed that granting `PACKAGE_USAGE_STATS` is sufficient on its own,
with the app-op left at its default. Changing an app-op is a heavier and less visible
intervention than a permission you explicitly approved, so it is not done.

## Future changes

This describes current behaviour, not a permanent guarantee about every future version. It
would be dishonest to promise that no network feature could ever be justified.

What the project does commit to: **any change to this position will be documented in this
file and in the release notes for the version that introduces it**, rather than appearing
silently.

## Questions

Open an issue at <https://github.com/rmpsdroid/BattInsight/issues>.
