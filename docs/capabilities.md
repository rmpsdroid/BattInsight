# Capability model

## Why eight states

Phase 0 proposed five. Measurement showed five insufficient. Each state below exists
because a real observation would otherwise have been indistinguishable from a different
one — and the specific failure both predecessor applications shipped was collapsing these
into "it works" or "check your permissions".

| State | Measured situation that requires it |
|---|---|
| `Available` | Source works, returned data |
| `AvailableNoEvents` | Android 16 returned 68 **named** kernel wakelocks with every counter zero, because that environment never suspends. Zero is correct there |
| `AvailableDegraded` | An app UID saw the same 98 UIDs as a shell but only 152 of 180 name mappings. Acquisition succeeded; naming did not |
| `PermissionMissing` | Platform named the exact permission. Carries it so onboarding can offer that grant instead of saying "check your permissions" |
| `NotSupported` | debugfs is unmounted on Android 12+ user builds. No permission and no privilege changes this |
| `SourceUnavailable` | Path absent or unreadable *here*, but may exist under another backend or device |
| `ExecutionFailed` | Process did not run, timed out, or produced something unrecognised |
| `Unknown` | Not probed. Never treat as either available or unavailable |

## Which layer assigns these

`CapabilityState` is **semantic** and is assigned by `CapabilityInterpreter`. The collection
layer reports mechanics only, through `CollectionOutcome`:

| `CollectionOutcome` | Meaning |
|---|---|
| `Data(bytes)` | Process ran, output carries a marker of the requested format |
| `Empty` | Process ran, exited 0, produced nothing. **Nothing more is claimed** |
| `PermissionDenied(permission, alternatives, rawDetail)` | Platform refused and named a permission |
| `SourceError(detail)` | Command reported a problem: unknown option, service unreachable |
| `ExecutionFailed(exitCode, detail)` | Did not run, timed out, or exited non-zero |
| `Unrecognised(detail)` | Output we cannot account for — never reported as success |

The split exists because `AvailableNoEvents` cannot honestly be concluded from a bare empty
result. `CapabilityInterpreter` reaches it only when given a `SourceReading` showing the
section was present with records but no values — the measured kernel-wakelock case. A
generic empty result with nothing inspected becomes `Unknown`, not `AvailableNoEvents`.

## The rule that matters most

**Empty is not failure.** Three measured cases:

- `UsageStatsManager.queryUsageStats` returns an **empty list** without access. It does not
  throw. Code treating "no exception" as "has access" reports a working collector that
  returns nothing forever.
- `NetworkStatsManager.querySummary` succeeded with zero buckets on an emulator with no
  traffic history — a correct answer, not a failure.
- Kernel wakelock counters were zero on a device that never suspended.

`AvailableNoEvents` exists so these stay distinguishable from `SourceUnavailable`.

## Do not add a boolean

`CapabilityState` deliberately exposes no `isAvailable` convenience property. Collapsing
these cases is precisely how the information gets lost, and `CapabilityStateTest` asserts
the distinctions so a future merge fails a test rather than passing silently.

## Probing rules

1. Attempt the actual operation. Never infer from privilege level.
2. Classify on content.
3. Cache against the current boot ID; re-probe after boot or permission change.
4. `Unknown` until probed — act as though absent and re-probe.

---

## How capabilities are evaluated

`CapabilityCoordinator` is the only component that talks to the platform. The UI observes
its `StateFlow<CapabilityReport>` and calls `refresh()`; it never queries `PackageManager`,
Shizuku, `BatteryManager`, `UsageStatsManager` or runs a command itself. Keeping that in one
place is what makes the picture internally consistent and testable.

Every dependency is an interface, so the whole evaluation runs on the JVM against fakes.
There is no polling: capability changes only when the user changes something, so refresh is
explicit. A refresh already in flight is cancelled first, so a slow evaluation can never
overwrite a newer one.

### Backends

| Backend | Status |
|---|---|
| `GRANTED_APP` | Implemented. Needs DUMP, PACKAGE_USAGE_STATS and INTERACT_ACROSS_USERS |
| `SHIZUKU_ADB` | Implemented. Needs none of those; measured at uid 2000, `u:r:shell:s0` |
| `SHIZUKU_ROOT` | **Not implemented, never measured** |
| `DIRECT_ROOT` | **Not implemented, never measured** |

The two root routes are represented rather than omitted, so the model is honest about what
is missing instead of quietly pretending the question does not exist. Neither has a fake
implementation.

Shizuku is preferred when both are usable: it was measured 2-4x faster and resolves UID
names an app UID cannot.

### Shizuku lifecycle

`NotInstalled`, `InstalledNotRunning`, `RunningNotAuthorised`, `RunningAuthorised`,
`VersionUnsupported`, `Error`, `Unknown` — separate because they behave separately.
Installing is not running, and running is not authorised: `pm grant` of Shizuku's own
`API_V23` permission reported success while Shizuku still refused, because it keeps its own
client authorisation list.

### Permission state

Reported per permission, never as a single boolean. The platform demands the three in
sequence, so a caller that only knows "not all granted" cannot tell the user which one to
grant next.

Usage access has **two valid routes** and either suffices: holding `PACKAGE_USAGE_STATS`,
or the `GET_USAGE_STATS` app-op being allowed. Requiring the app-op when the permission is
granted would contradict measurement — after `pm grant` the app-op stayed at `DEFAULT` and
the query still returned rows.

---

## Access mode is not capability

The user's chosen access mode says what they *want*; the capability report says what is
*true*. They are stored and computed separately and must never be conflated.

```
AccessMode.SHIZUKU_LIVE          does not mean  Shizuku is usable right now
AccessMode.GRANTED_APP           does not mean  the three permissions are held
```

A stored preference survives reboots; Shizuku does not. Permissions can be revoked from
Settings without telling the application. So readiness is always re-derived from a current
capability report, and there is deliberately no persisted "setup complete" flag.

`BackendSelection` joins the two and reports three separate facts — `preferred`, `active`
and `fallbackOffer`. A privileged mode is never silently substituted for another, because
the two modes differ in whether BattInsight itself ends up holding elevated permissions;
that is the user's decision, so an available alternative is offered rather than applied.

## Capabilities with no probe

Six of the eleven capabilities have no probe yet. They report `Unknown` with the reason
*"No probe implemented yet"* rather than being omitted from the report.

Omitting them was a real defect, found in Phase 3.1: the initial report listed all eleven,
evaluation returned five, and refreshing therefore made six capabilities disappear. A
capability missing from the report reads as one that does not exist, which is a claim the
application has not earned.
