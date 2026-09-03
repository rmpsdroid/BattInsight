# Security and privacy

## Local first

This application reads what a device has been doing: which apps ran, which held wakelocks,
which used the network. That is sensitive. The design position is that **it never leaves
the device** except in a file the user explicitly creates.

## Not declared

The Phase 2A manifest declares **no permissions at all**.

| Permission | Status |
|---|---|
| `INTERNET` | **Not declared.** No feature needs a network. An app that cannot reach the network is the strongest possible privacy statement for a tool that reads usage history |
| `QUERY_ALL_PACKAGES` | **Not declared.** Play-policy sensitive. See the UID-resolution investigation in `data-sources.md`; it must not be added as a shortcut |
| `BATTERY_STATS` | **Not declared.** Measured unnecessary, despite both predecessors requesting it |
| `READ_LOGS`, location, storage | **Not declared.** No feature requires them |
| `DUMP`, `PACKAGE_USAGE_STATS`, `INTERACT_ACROSS_USERS` | **Not declared yet.** Required by the granted-app backend and added in Phase 3/4 alongside it. Declaring a privileged permission before the feature exists is how manifests accumulate permissions nobody can justify |

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
