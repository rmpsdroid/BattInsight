# Session model

> **Design only. No implementation exists, and none belongs in Phase 2A.**

## Ownership

**The session engine owns charging state.** Battery statistics are corroboration, never
authority.

Inputs it will use:

- `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`, declared in the **manifest** so
  they survive process death
- `ACTION_BATTERY_CHANGED` sampling to reconstruct what happened while not running
- `BatteryManager` properties
- Persisted previous state
- Monotonic time (`elapsedRealtime`) for durations; wall clock for display only
- Cold-start reconciliation

### Explicitly not a dependency

Batterystats `dsd`/`csd` (discharge and charge step durations) are **optional diagnostic
corroboration only**. The session engine must not depend on them. They were absent on the
Android 16 emulator, and their availability on real modern hardware is unmeasured — but
this is not a blocker, because charging authority never rested with them.

## Failure modes being designed against

Each is a real, reported defect in the predecessor.

| Defect | Cause | Design answer |
|---|---|---|
| Charge sessions missed entirely | Unplug receiver registered at runtime inside a killable service; connect never registered at all | Manifest receivers plus cold-start reconciliation |
| Every reboot deleted all history | Boot receiver called `deleteAllRefs()` first thing | Record a boot ID. Refuse invalid comparisons; never delete |
| Stats reset at a fixed wall-clock time | Timestamps were wall-clock only, with the monotonic alternative commented out | Record both clocks; detect divergence |
| An update destroyed all data | Snapshots stored as one opaque blob with no schema version | Versioned rows, per-collector tables, tested migrations |
| `Period: n/a` in exports | Duration was a pre-formatted string; no from-timestamp field existed | Machine-readable from/to plus a snapshot UUID |
| Charge detected only at 100% | Threshold check at unplug time only | Observe charging as an interval, not an event |

## Snapshot record

Every field exists to make one reported failure diagnosable rather than mysterious.

`id` (UUID) · `bootId` · `elapsedRealtimeMs` · `wallClockMs` + `utcOffsetSeconds` ·
`counterGeneration` · `schemaVersion` · `capabilitySet` · `backendUsed` · `batteryState` ·
`chargeSessionId` · `trigger` · `appVersionCode` · `platformVersionAtCapture` ·
`sourceFormat` + `sourceFormatVersion`

`platformVersionAtCapture` catches an OS upgrade between snapshots — a comparability hazard
neither a boot ID nor a counter generation would detect. `sourceFormat` matters because the
routine acquisition format is not yet fixed and may change.

## Non-negotiable

**History is never silently discarded.** An invalid comparison is refused and explained,
with the underlying records retained. Deleting data to avoid a confusing UI is what both
predecessors did, and it is the single most damaging behaviour in their issue trackers.
