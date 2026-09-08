# Architecture

> Phases 2A-6. Collection, capability, access setup, the session engine and durable
> persistence exist. Decoding does not.

## Layering

Six layers, ordered so that a change in how data is *collected* can never corrupt data
already *stored*. That single property is what the predecessor lacked, and it is why an
upgrade there once deleted every user's history.

| Layer | Responsibility | State in Phase 2A |
|---|---|---|
| **Collection** | Obtain raw bytes and report execution mechanics. No interpretation, no policy | `PrivilegeBackend`, `BackendIdentity`, `SourceFormat`, `CollectionResult`, `CollectionOutcome` — contracts only |
| **Capability** | Decide what an outcome *means* for a given source | `Capability`, `CapabilityState`, `SourceReading`, `CapabilityInterpreter` |
| **Domain** | Normalise raw output into stable value types | `BatteryObservation` only; batterystats normalisation is not started |
| **Session engine** | Snapshot identity, session boundaries, comparability, reconciliation | `SessionEngine`, `BatterySession`, `BatterySnapshot`, `SnapshotComparability`. Pure Kotlin. See `session-model.md` |
| **Persistence** | Store snapshots durably with explicit schema versioning | `BattInsightDatabase`, `SessionDao`, `RoomSessionStateStore`, explicit entity mappers. Room, schema exported and committed. See `persistence.md` |
| **Access setup** | Turn a user's access choice into working access, and verify it | `AccessMode`, `SetupAction`, `SetupState`, `AccessSetupCoordinator` |
| **Presentation** | Screens, chart models, reports | Capability Centre, onboarding, Manage access, session history and session detail (Compose). No charts. See `ui-navigation.md` |
| **History query** | Read-only access to stored sessions for presentation | `SessionHistoryRepository`, `RoomSessionHistoryRepository`. Declares no writes |

Packages exist only where they hold real code. Empty packages were not created to complete
a diagram.

## Why the collection boundary is an interface

Two backends are planned and both were measured producing structurally identical output —
same version record, same 46 record tags, same 121 visible UIDs, same kernel wakelock count:

- **Granted app backend** — our own process, holding three permissions. No third-party app.
- **Shizuku shell backend** — measured at uid 2000, `u:r:shell:s0`. Needs none of our
  privileged permissions, runs 2–4× faster, and resolves UID names the app UID cannot.

Because their outputs are equivalent, one interface is honest rather than merely tidy. Both
are implemented and validated on Android 16.

## Why the access choice sits outside the capability layer

`CapabilityCoordinator` answers *what is possible*. It does not decide *what should be
used*, because that depends on a preference it has no business reading. A selector is
injected instead, and the coordinator applies whatever it returns.

That keeps three facts separate, which matters because conflating them is how an application
starts quietly doing something the user did not ask for:

- **preferred** — what the user chose;
- **active** — what will really run, which may be nothing;
- **fallbackOffer** — a working alternative deliberately *not* being used.

The UI renders this rather than computing it. A screen that worked out its own answer could
disagree with the one the collection layer acts on, and the user would be told something
false.

## Rules the layering enforces

1. **Capability is measured, never inferred from privilege.** `/sys/class/wakeup` on
   Android 16 is mode 0755 root:root yet unreadable from the shell domain — SELinux
   context decided it, not the UID. A design that reasons from privilege level gets this
   wrong.
2. **Exit status is necessary but not sufficient.** Every denial measured returned exit 0
   with the error on stdout, so content is checked first — but a non-zero exit remains real
   evidence of failure and is not ignored.
3. **Mechanics and meaning are different layers.** `CollectionOutcome` says what a process
   did; `CapabilityInterpreter` says what that means. The collection layer never concludes
   `AvailableNoEvents` from a generic empty result. See `capabilities.md`.
4. **The session engine owns charging state**, not the data source. See `session-model.md`.
5. **The UI computes no session arithmetic.** Views receive prepared models with explicit
   bounds. Chart code that derives its own time axis is where "the graph starts at the
   wrong time" bugs live.

## Deferred decisions

| Decision | When | Why not now |
|---|---|---|
| Compose vs Views | Phase 8 | The session engine matters more and is testable without either. A placeholder activity does not commit us |
| Dependency injection | When manual wiring hurts | Constructor injection covers swapping backends in tests |
| Collector tables (wakelocks, alarms, CPU) | When the decoder exists | A schema committed before the shape of the data is known is a migration waiting to happen |
| Routine acquisition format | Phase 7 | See `data-sources.md` |

---

## Command safety

The application will eventually run commands with elevated identity through Shizuku, so
there is deliberately **no** `execute(command: String)` in the public surface. Callers pick
a `ProbeCommand` from a sealed whitelist, and only that file maps one to an argument vector.
UI code cannot construct a command and nothing user-supplied reaches a process.

Four commands exist, all read-only: `id`, `id -Z`, `dumpsys batterystats --proto`, and
`dumpsys batterystats -c`. Adding one is a reviewable change to a single file, and tests
assert that no state-changing argument can appear.

A second, stricter whitelist governs the only operations that *change* state: `SetupAction`,
six entries, `pm grant` and `pm revoke` for three permissions against BattInsight's own
package name fixed at compile time. What crosses the Shizuku Binder is an identifier in both
cases, never a command, and the remote service resolves it against its own copy of the
whitelist before any process exists. See `security-privacy.md`.

Execution enforces a timeout, honours cancellation, captures stdout and stderr separately,
and records a nullable exit code — nullable because a process that never completed has no
exit status, and conflating that with "exited 0" is the mistake the whole architecture
exists to avoid. Captured output is bounded; payloads are never logged.

---

## How privileged output crosses the process boundary, from Phase 10A.3

The Shizuku backend runs its command in a process this application does not own, so the
capture has to cross a boundary to get here. Until Phase 10A.3 it crossed **by value**: the
`executeProbe` reply Bundle carried stdout as a byte array. That worked until a device's
statistics outgrew it.

A Samsung SM-M156B on Android 15 produced 1,066,676 bytes of `dumpsys batterystats -c`. The
reply parcel measured 1,048,800 bytes, the kernel refused the transaction, and the
application decoded the zero bytes that were left and told the user *"Android returned
nothing at all"* — about a platform that had just produced a megabyte of healthy output. The
last capture that had worked was 852,557 bytes, at 81% of the ceiling. Nothing had changed
in the application; the device's own statistics had simply grown past a number nobody had
chosen for a reason that would still hold.

**The reply now carries no payload.** It carries a protocol version and three
`ParcelFileDescriptor`s — standard output, standard error, and a completion frame — and every
byte travels through a pipe. Pipes are flow controlled by the kernel, so how large a capture
may be is no longer a function of a transaction budget. Raising the old ceiling would only
have moved the same failure to a larger device.

### Completion is framed, because a stream cannot carry an exit code

A payload stream ends at end-of-file, and end-of-file is also what a process being killed
produces. So the exit status, the truncation flags, the byte counts and any failure reason
are written as one small fixed record on a third pipe, **after** both payload streams have
ended and the child has been reaped.

Its absence is the point. A privileged process that dies mid-capture closes its descriptors
without writing a frame, so "the command finished and produced this much" and "the bytes
stopped and nobody recorded why" are distinguishable — and the second is reported as a
failure rather than handed to the decoder. A prefix of checkin output parses perfectly well
as far as it goes; its missing sections would read as sections the device does not have, and
the kernel wakelock block sits at 84–88% of the payload.

### Both sides drain concurrently, and only one side needed to

A process writing to a full pipe is suspended until somebody reads. A producer that drains a
child's stdout to its end before touching stderr therefore deadlocks against any command that
writes enough to standard error first: each side waits for the other. **Both backends had
exactly that shape**, independently, and both were corrected. The reader drains its two pipes
in parallel too, though that is not what prevents the deadlock — it is there so the reader
does not depend on how the producer happens to be threaded.

### The ceiling is a memory bound now, not a transport one

`CaptureLimits.MAX_CAPTURE_BYTES` is 16 MiB and is the **only** ceiling; the two backends
previously declared 1 MiB each, independently, which is how they came to disagree about one
device — the granted-app path truncated honestly while the Shizuku path failed outright.

16 MiB is chosen from what the application must hold, not from what a parcel will carry. The
decoder takes a `ByteArray`, so one complete capture is materialised once in this process;
accumulating it peaks at roughly three times its own size while the buffer doubles and is
copied, so about 48 MiB transiently at the ceiling. That is fifteen times the largest payload
ever measured on real hardware, so no genuine capture approaches it, and anything that does
is a runaway producer — stopped, and reported as truncated rather than decoded as complete.

**This is not a constant-memory pipeline, and it should not be described as one.** The
privileged process is constant-memory: it copies through one fixed buffer and never holds the
capture. This process still holds the finished payload once, because that is what the decoder
boundary takes.

### Setup actions kept the old mechanism deliberately

`executeSetupAction` is the only entry point that changes device state, and its whole output
is a line or two from `pm`. It still replies by value, bounded at 64 KiB. Giving the narrower
method the more capable transport would have widened a security surface to no purpose.

---

## Why the session engine is pure

`SessionEngine` has no Android import, no clock, no I/O and no randomness beyond an
identifier factory it is handed. Everything platform-shaped lives in `AndroidBatterySource`,
which maps intent extras to `BatteryObservation` and holds no session logic at all.

That split is what makes roughly eighty lifecycle scenarios — reboots, process death,
stale broadcasts, wall-clock jumps, contradictory battery states — run on the JVM in
milliseconds. Each is a function of its arguments, so a failure names one cause rather than
a race.

`SessionCoordinator` sequences observations and publishes state. It owns no decisions.

### Initialisation is a property of the coordinator, not a caller convention

`SessionCoordinator.begin()` reads persisted state and reconciles it against a current reading;
`observe()` accepts an ordinary reading against state already in memory. The order matters
absolutely, because `SessionEngine.accept` starts a fresh interval when it is handed a state with
no session — which is the correct answer for a genuinely new install and the wrong one for a
process that simply restarted.

That ordering used to be documented ("call once per process, before observe") and left to
callers. Production could not honour it. The start-up reading and the lifecycle-visible sampler
live in two independently scheduled coroutines — `viewModelScope` and `lifecycleScope` — and
Phase 10A.2 measured a Samsung SM-M156B splitting one continuous discharge interval into two open
sessions when the sampler won the race: `accept()` saw an empty state, opened a new interval on
top of a perfectly readable stored one, and the stored interval was then never reconciled and
never closed.

So the coordinator enforces it. **Whichever of `begin()` or `observe()` arrives first performs the
load-and-reconcile**, under the same mutex that already serialises observations; every later call
is an ordinary observation. An early reading is not discarded — reconciliation is defined as
"saved state plus a current reading", and an early observation is exactly such a reading.

Two details carry weight. There is no waiting primitive, so there is nothing to dead-lock on,
nothing whose cancellation could strand other observers, and no timeout to tune. And the
coordinator counts itself initialised only when a reconciliation was actually *adopted*: a reading
rejected as contradictory, or a state that failed to persist, adopts nothing, so the next
observation reconciles again rather than being accepted against empty state — which would
reintroduce the split one step later.

### Where process-lifetime facts live

Purity has one consequence worth naming: the engine cannot know whether *this process* just
started, because that is a platform fact and the engine holds no platform state. The Android
side has to supply it, and it has to supply it at the right scope.

`ProcessStartGate` is that state, and it is deliberately process-scoped rather than
Activity- or `ViewModel`-scoped. An Activity is destroyed and recreated on rotation; a
`ViewModel` outlives that but is cleared when the Activity finishes, so a back-press and
relaunch inside one living process would look like a new process to either of them. Only the
process itself has the lifetime of the claim being made, and static state in a loaded class
has exactly that lifetime — created with the process, gone with it, never written to disk.

This is what keeps `SessionTrigger.APP_START` honest. It means "the process started" and is
issued once per process; a UI that merely came back into view reports `APP_VISIBLE`. The
series builder turns the first into `PROCESS_RESTART` and lets the second fall through to the
ordinary spacing test, so a backgrounded application is never reported to its user as a
crashed one. See `docs/time-series.md` for the gap semantics and the Phase 10A.1 measurement
that forced the distinction.

## What the session engine must never depend on

It holds no reference to the capability or access layers, which is the strongest form that
guarantee can take. A battery interval is a fact about the device; it cannot move because
the user changed access method or a permission was revoked.

The dependency runs one way only: nothing in `session/` imports from `capability/`,
`setup/` or `access/`.

It imports exactly one thing from `collection/` — `SourceFormat`, a three-value enum naming
the acquisition formats — so `CounterSource` can describe which of them produced a snapshot's
counters. That is a value type, not behaviour; duplicating the enum to claim zero imports
would be worse than the coupling it removes.

## Sampled series, from Phase 9B

A new pure package, `series`, sits beside `session` and `batterystats` and imports neither Room
nor Compose.

| Type | Responsibility |
|---|---|
| `BatterySeriesBuilder` | turns ordered samples into segments and gaps |
| `CounterSeriesBuilder` | turns ordered captures into adjacent intervals, via the existing delta engine |
| `CounterRetentionPolicy` | which captures may safely be evicted — the three-comparison rule |
| `BatterySampleStore` | the persistence boundary; `RoomBatterySampleStore` implements it |
| `BatterySampler` | when a reading becomes a stored sample, and the coalescing rule |

The division that matters: **connectivity is decided in the domain, not in the chart.** Phase
9C receives segments and gaps and has no way to express "join these two points", because two
segments are never adjacent without a gap between them.

`CounterRetentionPolicy` lives here rather than in the Room store deliberately. It is a policy
over the comparability engine rather than a persistence detail, and keeping it out of
persistence is what makes its edge cases testable — the store legitimately rejects some of them
before they could ever be written.

See [`time-series.md`](time-series.md).

## Visualization, from Phase 9C

A `chart` package sits between the pure `series` domain and Compose, and imports neither Room
nor Canvas.

| Type | Responsibility |
|---|---|
| `BatteryChartMapper` | domain series → chart model, preserving segments and gaps exactly |
| `CounterChartMapper` | domain intervals → interval blocks, per wakelock family |
| `RenderPlanner` | chart model → `LineStrip` / `PointMarker` / `GapMarker` / `IntervalBar` / `RefusedInterval` |
| `GapCopy` | exhaustive plain language for all six gap reasons |
| `SessionChartLoader` | reads the stored series and returns chart models; the only DB touch |

The render primitives exist so that "no line crosses a gap" is a property a JVM test can check
by counting strips, rather than a claim about anti-aliased pixels. The Compose layer scales,
strokes and labels; it makes no decision about connectivity, comparability or missing values.

No chart dependency was added. See [`time-series.md`](time-series.md).
