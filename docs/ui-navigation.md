# Screens and navigation

What BattInsight shows, how the screens reach their data, and what the UI is deliberately not
allowed to do.

## Screens

| Screen | Purpose |
|---|---|
| Setup | Choosing an access route, once |
| Capability Centre | The current session, capability state, and a manual capture |
| **History** | Every battery period recorded, newest first |
| **Session detail** | One period in full |
| Manage access | Changing or removing access |

History and session detail are new in Phase 8. Everything else predates it.

## Navigation

Plain state in the view model — a `Screen` sealed interface and a `MutableStateFlow`.

Navigation Compose was considered and not adopted. The graph is five destinations with one
argument between them; a navigation library would add a dependency, a route DSL and an argument
encoding to solve a problem a sealed class already solves in four lines. It is worth revisiting
when there is a graph worth describing, and not before.

`Screen.SessionDetail` carries the session id as a constructor parameter, so an id cannot be
forgotten or mistyped into a route string.

**Back** goes detail → history → Capability Centre, matching how a person got there. Nothing
intercepts the gesture itself, only the destination it resolves to, so predictive back keeps
working.

## The query boundary

```
Composable
    ↓  state only, no suspend calls, no queries
ViewModel
    ↓  SessionHistoryRepository  (interface, read-only)
RoomSessionHistoryRepository
    ↓
SessionDao / CounterDao
```

Two properties are enforced by the shape rather than by discipline:

**No Composable sees a database row.** `SessionEntity` never leaves the persistence package.
The repository returns `SessionHistoryRow` and `SessionDetail`, which are pure domain types
with no Room, no Android and no formatting. The predecessor rendered rows directly, so a column
rename broke screens instead of one mapper.

**The history screens cannot write.** `SessionHistoryRepository` declares no insert, update or
delete. Not "does not call one" — cannot express one.

Reads are suspending rather than reactive. History changes when a capture is taken or a session
boundary occurs, and the view model already knows about both, so a Flow would add invalidation
machinery for an event the caller triggers itself.

## History works without access

Browsing saved periods touches only BattInsight's own database. It works when Shizuku is not
running, when no permission was ever granted, and when the chosen access route has stopped
working. Only a live capture needs a backend.

That is asserted by a test that builds the repository with nothing but the two DAOs.

## Scale

A bounded query of 50 rows with an explicit "Show older periods", not Paging 3.

Phase 6 retains history indefinitely, so loading all of it would eventually be wrong — but a
session is roughly one charge cycle, so a heavy user produces a few hundred rows a year. Paging
3 solves thousands of rows arriving during a scroll, and costs a `PagingSource`, a `Pager`,
differ-based list state and a testing story for all of it. A bounded query stops the unbounded
read just as effectively in a few lines that test on the JVM.

Ordering is by the start snapshot's wall clock, descending, with the snapshot id breaking ties.
Without a deterministic tiebreak two sessions sharing a millisecond could return in different
orders on different runs, and paging would then skip or repeat rows.

**No schema change was required.** The join is by primary key, and sorting a few hundred rows
is not measurable. An index is worth adding when a measurement says so.

## Wall clock versus monotonic clock

Both are stored, and they answer different questions:

| | Used for | Never used for |
|---|---|---|
| Wall clock | displaying when something happened, ordering the list | any duration or comparison |
| Elapsed realtime | durations, ordering within a boot, comparability | display |

A duration computed from wall clocks is wrong whenever the clock moved — a time-zone change, an
NTP correction, a manual adjustment — and can come out negative. A test seeds a session whose
wall clock runs *backwards* between its two snapshots and asserts the duration is still right.

## What the wording is not allowed to do

Three rules, each with tests.

**No enum name reaches a user.** Every `CounterDeltaReason` has plain-language copy, long and
short. The mapping is an exhaustive `when`, so a reason added later fails to compile rather
than falling through to "something went wrong".

**Nothing unknown renders as zero.** A missing battery level is "unavailable", not 0%. An
unmeasurable duration is "unknown", not 0 s. A 40 ms wakelock is "40 ms", not "0 s". On a
battery screen every one of those zeros reads as "nothing happened".

**Inference is never dressed as observation.** A boundary reconstructed after a process gap
says so; it does not say "Unplugged", which would assert a broadcast was received. The four
non-trivial end reasons stay distinct rather than collapsing into "Restarted" — a proven
reboot, a real change nobody witnessed, an absence of proof either way, and data disagreeing
with itself mean different things to someone troubleshooting.

## Counter presentation

A refused comparison shows **no figures at all**. Phase 7B.1 established that one decreased
counter makes every counter in the same capture untrustworthy, so a partial list would be
showing numbers already known to be wrong. The screen explains why instead, and a UI test
asserts neither delta section renders.

A comparable pair where nothing moved says "No increase was recorded — that is a measurement,
not missing data", which must not read like an empty screen.

Top five per family, ordered by duration, then count, then name. The name tiebreak keeps the
list stable: without it, two counters with identical figures could swap places between
recompositions and look like data changing when nothing did.

## App names are enrichment, not attribution

A UID resolves through `PackageManager` at display time. Package mappings are **not** persisted
— Phase 7B decided that deliberately — so a name says what runs under that UID *today*, which
is not proof of what ran under it when the capture was taken.

So the name never replaces the number, never appears without it, and the screen says plainly
that the UID is the reliable part.

## Not here yet

No charts of any kind. Phase 9 defines chart semantics once this presentation is stable, and
adding a sparkline now would commit the project to a sampling model it has not designed.

No export, no notifications, no widgets, no background refresh. Capture happens when someone
presses the button.
