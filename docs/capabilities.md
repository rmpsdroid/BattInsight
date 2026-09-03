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
