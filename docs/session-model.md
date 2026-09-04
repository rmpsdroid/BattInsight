# Session model

> **Implemented in Phases 5 and 6.** The engine, the time model, boot identity,
> comparability and cold-start reconciliation all exist and are tested, and Phase 6 made
> them durable. See [persistence.md](persistence.md) for the storage side.

## Ownership

**The session engine owns charging state.** Battery statistics are corroboration, never
authority.

Inputs it will use:

- `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`, context-registered in Phase 5
  (see *Receivers* below for why not the manifest yet)
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

---

# Implementation (Phase 5)

## Two clocks, and what each is for

`CaptureTime` records both, always, because neither can answer the other's question.

| | Used for | Never used for |
|---|---|---|
| `elapsedRealtime` | Ordering, durations, transition timing | Display |
| Wall clock + UTC offset | Display, export, human timestamps | Any duration |

The offset is stored rather than derived later, because it is not recoverable afterwards: a
device that changes timezone cannot reconstruct what its own clock read at a past moment.

A user changing the clock, crossing a timezone or hitting a DST boundary cannot alter a
measured duration. That is asserted directly, including a test that runs the same monotonic
sequence twice — once with a well-behaved wall clock and once with one lurching six hours in
both directions — and requires identical durations.

## Boot identity

`elapsedRealtime` restarts at zero on every boot, so two readings can only be ordered once
they are known to share one. `BootIdentity` answers in three ways, not two:

| Variant | Source | Can prove SAME | Can prove DIFFERENT |
|---|---|---|---|
| `Kernel` | `/proc/sys/kernel/random/boot_id` | yes | yes |
| `Derived` | wall clock − elapsed realtime | **no** | **no** |
| `Unknown` | nothing available | no | no |

Only the kernel identifier decides anything. Every other combination is `UNKNOWN`.

**Measured on Android 16:** `boot_id` is readable by an ordinary application
(`exists=true canRead=true`, 36 bytes), so the strong variant is what runs in practice. The
fallback exists because that is a platform behaviour, not a guarantee.

### Why the fallback proves nothing in either direction

An earlier version treated a large change in the *estimated* boot time as proof of a reboot.
That was unsound and was corrected in Phase 5.1.

`System.currentTimeMillis` may be changed by the user or the network and may jump either way
at any moment; `SystemClock.elapsedRealtime` continues undisturbed. So on one uninterrupted
boot a six-hour clock correction moves `wallClock − elapsedRealtime` by six hours. Reading
that as a reboot would split a real session and label the break *device restarted* — a
confident false statement, which is worse than an honest "cannot tell".

The estimate is retained as diagnostic metadata for exports, where an approximate boot time
helps a human read a timeline. It may not decide anything.

### Android clock semantics, precisely

| | Advances during deep sleep | Affected by clock changes | Resets on reboot |
|---|---|---|---|
| `SystemClock.elapsedRealtime()` | **yes** | no | yes |
| `SystemClock.uptimeMillis()` | no | no | yes |
| `System.currentTimeMillis()` | yes | **yes** | no |

`elapsedRealtime` is the engine's monotonic source precisely because it includes deep sleep:
a device asleep on battery for six hours has still been discharging for six hours.
`uptimeMillis` would under-report exactly the intervals a battery tool exists to measure, and
is not used anywhere.

## What owns a session boundary

Power attachment, and nothing else.

`SessionType` follows `PowerAttachment`, which is resolved from the plug when the plug says
anything, and from status only when the plug is `UNKNOWN`. Consequences that fall out of
that, all tested:

- **`FULL` while plugged** stays a charge interval. The user has not unplugged anything.
- **`NOT_CHARGING` while plugged** — a charge limit holding at 80% — also stays a charge
  interval, for the same reason.
- **`DISCHARGING` while plugged** — real under heavy load — stays a charge interval, and the
  contradiction is recorded on the observation rather than smoothed away.

### What is explicitly not authority

**Battery level.** It is noisy, recalibrated, frozen for long periods, and it jumps. A level
that rose does not mean charging began; one that fell does not mean it ended. Level is
carried for diagnostics and never consulted by the engine.

**Batterystats `dsd`/`csd`.** Absent entirely on the Android 16 emulator measured in Phase
1A. A session model depending on them would have no answer on that device.

**The application's own lifetime.** Process death is not a session boundary.

## Process death versus reboot

Modelled separately, because they are different things:

| | Boot identity | `elapsedRealtime` | Session |
|---|---|---|---|
| Process death | same | continues | continues, same identity |
| Reboot | different | restarts at zero | closed at a boot boundary |

A reboot ends the interval even if the cable never moved, because the timeline it was
measured on no longer exists.

## Cold-start reconciliation

The application cannot observe anything while its process does not exist, so this is where
correctness after process death actually comes from.

| Situation | Result |
|---|---|
| Nothing saved | start |
| Same boot (**proven**), same direction | continue, same session identity |
| Same boot (**proven**), different direction | boundary, `RECOVERY`, trigger `RECOVERY` |
| Different boot (**proven**) | boundary, `BOOT_BOUNDARY`, trigger `BOOT_CHANGED` |
| Boot unprovable, time progressing | boundary, `UNPROVEN_CONTINUITY`, trigger `RECOVERY` |
| Boot unprovable, time went backwards | boundary, `INCONSISTENT_STATE`, trigger `RECOVERY` |
| Saved time later than present, **proven** same boot | rejected, `INCONSISTENT_STATE` |

Row three matters most in normal use. No broadcast was seen, so labelling it
`POWER_DISCONNECTED` would be claiming one was.

### Four boundary reasons, four different claims

Phase 5.1 separated these, because they were previously saying more than was known.

| Reason | What BattInsight actually knows |
|---|---|
| `POWER_TRANSITION` | A transition was **observed** |
| `BOOT_BOUNDARY` | A reboot is **proven** — requires a kernel boot identifier |
| `RECOVERY` | Same boot proven, and the device is **known** to have changed while the process was gone. Only the broadcast is missing |
| `UNPROVEN_CONTINUITY` | **Nothing** is known to have changed, and nothing is known to be wrong. Continuity simply cannot be established |
| `INCONSISTENT_STATE` | The saved timeline is **disproven** — monotonic time went backwards |

The last two both start a fresh interval and **neither claims a reboot**. Monotonic time
going backwards disproves the saved timeline but does not, on its own, prove a restart:
stale or corrupt saved state produces the same reading, and only a kernel identifier could
tell the two apart. Saying "the device restarted" there would invent the one fact this model
is most careful never to invent.

The practical consequence of the fallback proving nothing is worth stating plainly: on a
device where `boot_id` is unreadable, every cold start begins a fresh interval. That is a
real loss of function, and it is the correct one — the alternative is claiming a continuity,
or a reboot, that was never established.

`SessionTrigger.isObserved` exists so the distinction between witnessed and inferred is
queryable, and the UI says *"Change detected at start-up (not observed directly)"* rather
than presenting an inference as an observation.

## Counter generation

`CounterGeneration` exists because Android's counters can reset independently of anything
BattInsight decides — `dumpsys batterystats --reset` does it, and other software invokes
that.

A **proven** boot boundary also advances the generation. That is a comparability policy of
ours rather than a claim about the platform: with no counter reset detector yet, BattInsight
declines to subtract across a boundary it cannot reason about, instead of asserting that
every counter physically restarts at every reboot.

It moves independently of the session, in both directions:

- counters can reset mid-discharge, which does **not** end the interval;
- a session boundary resets **no** counter.

Both are asserted. Conflating them is why predecessor tools produced impossible deltas after
a reset.

No production detector exists yet — that needs the decoder Phase 7 owns. The transition is
modelled now so the comparability rules have something real to be tested against, rather
than being retrofitted later around data that already exists.

## Comparability

`SnapshotComparability` asks two separate questions, because they have different
requirements:

| | Needs |
|---|---|
| `forDuration` | same boot (proven), correct monotonic order |
| `forCounters` | all of the above, plus same generation, same schema, compatible source |

A boot change fails both. A counter reset fails only the second — the clocks are unaffected
by `--reset`.

Every refusal is a typed reason with a sentence a person can read. `DeltaResult` then has
three outcomes, because "no number" has two distinct causes a user needs told apart: the
comparison was refused, or it was permitted and the data was not there. The predecessor
rendered both as a blank cell.

**Nothing is ever deleted to avoid a confusing comparison.** The refusal is explained and
the records are kept.

## Receivers, and an honest limitation

Phase 5 registers all three broadcasts on a `Context`, `RECEIVER_NOT_EXPORTED`, while
something is collecting, and unregisters when nothing is.

**A context-registered receiver observes nothing while the process does not exist.** A user
who unplugs with BattInsight not running produces no broadcast anyone hears. That is stated
rather than glossed, and it is why cold-start reconciliation exists.

Two alternatives were considered and both declined for now:

- **A manifest receiver** would survive process death, and `ACTION_POWER_CONNECTED` is among
  the implicit broadcasts still exempt from the Android 8 background limits. Phase 5 declined
  it because there was nowhere to record what it saw. Phase 6 removed that objection and
  re-evaluated it against ten criteria anyway, and still declined: it buys boundary
  *timestamp* precision for data nothing yet displays, at the cost of a permanent background
  component and a silent-degradation mode under background restrictions. The full evaluation
  is in [persistence.md](persistence.md).
- **A foreground service** is refused. Running permanently with a permanent notification, to
  avoid an occasional inference, is a bad trade for a diagnostics tool.

`RECEIVE_BOOT_COMPLETED` is **not** requested, and Room did not change that. The engine
identifies a reboot from boot identity at the next start, with the same certainty whether it
learns of it immediately or hours later, so nothing needs to launch at boot.

The application declares no receiver of its own. The merged manifest contains exactly one,
`androidx.profileinstaller.ProfileInstallReceiver`, arriving transitively with Compose; it
is exported by design so `adb shell cmd package compile` can reach it, and guarded by
`android.permission.DUMP`. A test pins that guard.

## Persistence

`SessionStateStore` is implemented by `RoomSessionStateStore`, and session state now
survives process death — proved on a real device by killing the process that wrote it and
recovering the same session id in another. `InMemorySessionStateStore` remains, because the
engine's rules must stay testable without a database.

The seam is what kept the engine pure: `SessionStateStore` names no Room, SQLite or Android
type, and a test fails the build if anything in this package imports one.

Storing the model in preferences or ad-hoc JSON was never on the table. That is exactly the
opaque, unversioned blob that destroyed the predecessor's history on update. What replaced it
is explicit typed columns, a committed schema, and no destructive migration fallback —
see [persistence.md](persistence.md).
