# Security and privacy

## Local first

This application reads what a device has been doing: which apps ran, which held wakelocks,
which used the network. That is sensitive. The design position is that **it never leaves
the device** except in a file the user explicitly creates.

## Not declared

The manifest declares exactly three permissions, all for the optional granted-app backend.

| Permission | Status |
|---|---|
| `INTERNET` | **Not declared.** No feature needs a network. An app that cannot reach the network is the strongest possible privacy statement for a tool that reads usage history |
| `QUERY_ALL_PACKAGES` | **Not declared.** Play-policy sensitive. See the UID-resolution investigation in `data-sources.md`; it must not be added as a shortcut |
| `BATTERY_STATS` | **Not declared.** Measured unnecessary, despite both predecessors requesting it |
| `READ_LOGS`, location, storage | **Not declared.** No feature requires them |
| `DUMP`, `PACKAGE_USAGE_STATS`, `INTERACT_ACROSS_USERS` | **Declared**, for the optional granted-app backend. None is a runtime-dialog permission and declaring them does not make them available: they arrive only through ADB or a Shizuku shell session. The Shizuku backend needs none of them |

No telemetry, analytics, crash reporting or advertising dependency exists, and none will be
added without an explicit decision.

## Backup

`allowBackup="false"`, with both `backup_rules.xml` and `data_extraction_rules.xml`
excluding every domain.

Battery statistics and diagnostic output must never be cloud backed up. Beyond privacy,
restoring another device's snapshots would **corrupt session identity**: boot IDs and
counters would not match the device they were restored onto. The predecessor backed up its
entire sandbox, including its statistics database.

## Components

One exported component, the launcher activity, as a launcher entry requires. No exported
services or receivers, no `FileProvider`, no custom intent actions. When file sharing is
added it will be scoped to a single dedicated export directory; the predecessor exposed the
entire external storage root.

## Execution

- Shell execution: fixed command set, no user-controlled input in any command, and no
  state-changing batterystats argument. See `data-sources.md`.
- `hidden_api_policy` will **never** be modified. It is a device-wide security downgrade
  affecting every installed app, and it no longer delivers the access it once did.
- Root: not required, not implemented, and no root environment has been measured. If ever
  added it would be for granting our own permissions only, never for collection, never
  persistent, never system-wide.
- `PendingIntent`: always immutable unless mutability is required and justified.

## Secrets and signing

No keystore, credential or secret exists in this repository. `.gitignore` excludes `*.jks`,
`*.keystore`, `local.properties` and credential files, and a secret scan runs before each
commit.

The archived predecessor committed encrypted release keystores to its repository. **No
inherited signing key will ever be used.** When release signing is established it will use a
key generated for this project, stored outside the repository and injected via CI secrets.

---

## Capability probing and data handling

The capability layer runs read-only commands and inspects the results. What it does with
the output matters, because battery statistics contain application and usage information.

- **Nothing is written to disk.** Probe output is inspected in memory and discarded. No
  batterystats payload is persisted.
- **Payloads are never logged.** `ExecutionOutput.toString()` reports byte counts and
  timings only, and a test asserts payload bytes cannot appear in it.
- **Captured output is bounded** — a hard ceiling on bytes read from a process, and a
  bounded decoded prefix for classification. A checkin payload is around 800 KB and there
  is no reason to hold or decode more than is inspected.
- **Counts, not content.** The usage probe returns a row count, never usage records. The
  package probe returns how many UIDs resolved, never the installed package list.
- **No installed package list, usage history, full batterystats or kernel wakelock names
  are logged.**

## The capability layer changes nothing

The capability layer observes only. It executes no `pm grant`, changes no app-op, alters no
setting, and requests no Shizuku authorisation. The Capability Centre offers no Grant or
Install buttons; it links to *Manage access*, where any change is made deliberately.

---

## Access setup: the only thing that changes state

Setup is the one part of the application permitted to alter the device, so its constraints
are tighter than anything above.

### Two security models, chosen by the user

| | Live Shizuku | Independent granted-app |
|---|---|---|
| BattInsight holds `DUMP` / `PACKAGE_USAGE_STATS` / `INTERACT_ACROSS_USERS` | **No** | **Yes**, until revoked |
| Privileged work happens in | A process Shizuku owns | BattInsight's own process |
| Needs Shizuku running | Yes | No |
| Verified on Android 16 | Acquisition succeeded with all three denied to BattInsight, in the same run its own process was refused | Acquisition succeeded with Shizuku stopped |

These are not interchangeable: one permanently elevates this application's privileges and
the other does not. The application therefore **never switches between them silently**. When
a chosen route is unavailable and the other would work, that is reported as an offer and
nothing changes until the user accepts it.

### The typed setup boundary

`SetupAction` is the only state-changing surface, and it is a closed set of six:

```
grant_dump                     /system/bin/pm grant  com.rmpsdroid.battinsight android.permission.DUMP
grant_package_usage_stats      /system/bin/pm grant  com.rmpsdroid.battinsight android.permission.PACKAGE_USAGE_STATS
grant_interact_across_users    /system/bin/pm grant  com.rmpsdroid.battinsight android.permission.INTERACT_ACROSS_USERS
revoke_dump                    /system/bin/pm revoke com.rmpsdroid.battinsight android.permission.DUMP
revoke_package_usage_stats     /system/bin/pm revoke com.rmpsdroid.battinsight android.permission.PACKAGE_USAGE_STATS
revoke_interact_across_users   /system/bin/pm revoke com.rmpsdroid.battinsight android.permission.INTERACT_ACROSS_USERS
```

Properties that tests enforce rather than documentation asserting:

- the **identifier** crosses the Binder, never a command. `IProbeService.executeSetupAction`
  takes an action id and has no package parameter, so it cannot be asked to alter another
  application;
- the target package is a compile-time constant, asserted equal to `BuildConfig.APPLICATION_ID`;
- every argument vector is exactly four elements — absolute `pm` path, verb, package,
  permission — with no shell, no operator, no interpolation;
- an unrecognised identifier is refused before a process is created;
- no action names `BATTERY_STATS`, `INTERACT_ACROSS_USERS_FULL`, or any app-op.

### A grant is not trusted to have worked

`pm grant` reporting a clean exit is not proof; Phase 1B measured a grant reporting success
while the thing that mattered was kept elsewhere. So the sequence:

1. reads the permission before the step;
2. executes exactly one typed grant;
3. re-reads the permission, which is the authority;
4. stops immediately if it did not change, reporting what *did* change rather than a bare
   failure;
5. and only after all three, runs a real acquisition through BattInsight's own process.

Setup is reported ready only when that acquisition succeeds. Three permissions that look
granted while reading fails is reported as a verification failure, not smoothed over.

### App-operations are read, never written

Phase 1B measured `PACKAGE_USAGE_STATS` granted, `GET_USAGE_STATS` left at `DEFAULT`, and
`queryUsageStats` still returning 70 rows. Forcing the app-op is therefore unnecessary, and
it is a heavier and less visible intervention than a permission the user approved. It is
observed diagnostically and never mutated. Re-confirmed on Android 16 in Phase 4: the app-op
read `default` before and after a full three-permission grant.

### Removal

The three permissions can be revoked from inside the application through the same typed
actions when Shizuku is available, after an explicit confirmation. When it is not, the exact
`adb shell pm revoke` commands are shown instead. Shizuku's own client authorisation is
**not** touched: it lives in Shizuku, and users are directed there rather than having their
Shizuku configuration edited behind their back.

## Persisted state

> **Changed in Phase 7B.** Phase 7A said no privileged data was persisted at all. That is now
> too strong: the raw payload is still never stored, but a small verified subset decoded from
> it is. The distinction is set out immediately below, and it is the difference between
> storing a 900 KB dump of a device and storing two dozen numbers from it.

### Privileged captures: what survives the capture

| Stored | Not stored |
|---|---|
| kernel wakelock name, total ms, count | the raw payload, in any column or file |
| app wakelock numeric UID, tag, total ms, count | package names |
| checkin/record/parcel versions, platform fingerprints | battery history lines |
| capture time, boot identity, counter generation | undecoded record types |
| payload size and digest | decode warning text |

Bounded per battery session: one baseline capture and one latest, whatever the refresh count.

**No package attribution is persisted.** The `uid` records are decoded and used for live
display, and are not written to the database. A UID is a number; a package list is an
inventory of what a person runs.

The database stays app-private, cloud backup remains disabled for every domain, and none of
this is logged or leaves the device.

Two things: the access mode the user chose, and the user's own battery sessions — the
charge and discharge intervals BattInsight observed, plus the readings that bound them
(level, temperature, voltage, charge counter, plug source, health) and the times they were
taken.

Those readings come from the *public* `ACTION_BATTERY_CHANGED` broadcast that every
application on Android can see. They are not privileged output, they name no packages, and
they describe no other application.

Still **not** written to disk, deliberately: privileged payloads, package lists, permission
text and capability reports. Probe output is inspected in memory and discarded.

Two identifiers are stored per reading. One is a UUID this application generates, meaningless
elsewhere. The other is the kernel boot identifier, which changes on every restart and is
what distinguishes a reboot from a clock change; it never leaves the device, and cloud backup
remains disabled, so it cannot be restored onto different hardware.

Nothing is ever deleted on the user's behalf. A version that cannot read what an earlier one
wrote reports `MIGRATION_FAILURE` and leaves the data alone; there is no destructive
migration fallback, and a test fails the build if one is added.

There is deliberately no `onboardingCompleted` flag. Shizuku stops on reboot and permissions
can be revoked from Settings; a stored completion flag would keep asserting readiness the
device no longer has. Readiness is always re-derived from a current capability report.
