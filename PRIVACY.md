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

An application that cannot reach the network is the strongest privacy statement available
to a tool that reads usage history, and that is the current design.

## Data storage

Any data the application collects in future will be stored **on the device only**, in the
application's own private storage, and will leave the device only in a file the user
explicitly chooses to create and share.

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

The application currently declares **no permissions at all**.

Collecting battery statistics will eventually require three privileged permissions
(`DUMP`, `PACKAGE_USAGE_STATS`, `INTERACT_ACROSS_USERS`), granted by the user over ADB or
through Shizuku. They will be declared when the feature that needs them exists, and each
will be explained in the application at the point it is requested.

Two permissions are deliberately excluded and would require a separate, documented
decision to introduce:

- `INTERNET` — nothing in the design needs it.
- `QUERY_ALL_PACKAGES` — sensitive under app-store policy and avoidable.

## Future changes

This describes current behaviour, not a permanent guarantee about every future version. It
would be dishonest to promise that no network feature could ever be justified.

What the project does commit to: **any change to this position will be documented in this
file and in the release notes for the version that introduces it**, rather than appearing
silently.

## Questions

Open an issue at <https://github.com/rmpsdroid/BattInsight/issues>.
