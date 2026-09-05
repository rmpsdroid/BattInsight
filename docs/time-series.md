# Sampled time series

What BattInsight samples, how much of it is kept, and the rules any future chart has to obey.

Phase 9B builds the storage. **It draws nothing.** Phase 9C owns visualization.

## Two data families, two economies

Measured on a real Android 16 capture during the Phase 9A research:

| | cost | cadence |
|---|---:|---|
| one battery reading | **343 bytes** | every 5 minutes while the UI is visible |
| one full counter capture (repeated text) | 103.8 KB | — |
| one counter capture (interned, as shipped) | **~46 KB** at the retention target | only when the user asks |

A counter capture costs well over **100×** a battery reading and takes about 1.15 s of privileged
work. Sampling both on one cadence would waste either resolution or storage by two orders of
magnitude, so they are separate tables with separate triggers and separate retention.

## Lifecycle-visible sampling

Three phrases that are **not** synonyms, and Phase 9A used them interchangeably by mistake:

| term | meaning | used for |
|---|---|---|
| **lifecycle-visible** | the Activity is at least `STARTED` | the 5-minute battery cadence |
| **process-lifetime** | the process exists, visible or not | the existing battery broadcast collection |
| background | a dead process is woken | **nothing in BattInsight** |

The cadence runs inside `repeatOnLifecycle(Lifecycle.State.STARTED)` on the single Activity's
`lifecycleScope`. That primitive cancels the block on `STOPPED` and runs it afresh on
`STARTED`, so "the timer cannot outlive a visible UI" is a property of the structure rather
than a rule someone has to keep obeying.

Not used, deliberately: `ProcessLifecycleOwner`, foreground services, `WorkManager`,
`AlarmManager`, manifest receivers. Nothing wakes a dead process, and no new dependency was
added — `lifecycle-runtime-ktx` was already present.

**The consequence is accepted rather than hidden.** The series will have large gaps covering
most of every day. They are rendered as gaps.

**Measured, not assumed.** `tools/series-lifecycle-proof.sh` presses HOME so the Activity
reaches `onStop` while the process keeps running, then waits 380 seconds — longer than one
300-second production cadence. The pid is recorded before and after and must match, so "no
samples appeared" cannot be explained away by the process having died. Result: same pid, zero
new samples. A separate step force-stops the app and shows the samples either side of a real
process death survive with an unobserved interval between them.

### What produces a sample

| source | trigger | rule |
|---|---|---|
| becoming visible | `APP_START` | always, after the engine reconciles the reading |
| cadence tick | `PERIODIC` | only if nothing was stored for that session within the cadence |
| accepted broadcast | the observation's own trigger | always |

The asymmetry is deliberate: a broadcast carries a real level change, which is more
informative than a timer asking the same question, so a tick is coalesced away rather than the
other way round.

`SessionTrigger.PERIODIC` is finally *produced* here. It has existed in the enum, with
user-facing copy, since Phase 5 and nothing ever emitted it.

## Retention

| | bound | kind |
|---|---:|---|
| battery samples per session | **300** | **hard cap** |
| counter captures per session | **8** | **soft target** |

Both **PROVISIONAL UNTIL SUPPORTED PHYSICAL-HARDWARE VALIDATION**.

The asymmetry is the important part. Samples come from a timer and each is individually
disposable, so an unbounded count is a runaway risk with no compensating value — 300 samples
is 25 hours of continuous visible UI, far more than any real session accumulates.

A counter capture may be the **only evidence that Android's accounting broke**. Captures are
manual, so overflow is bounded by how many times a person pressed refresh. When nothing can
safely be removed, nothing is: the session keeps more than eight and that is the correct
outcome.

### Battery eviction, and the watermark

Oldest first, in the same transaction as the insert, so no committed state ever shows 301 rows.

`battery_sessions.battery_samples_evicted_through_elapsed_millis` records the **greatest
elapsed time actually deleted** — never the oldest surviving row. The distinction is
load-bearing: eviction removes oldest-first, so the greatest deleted value sits strictly below
the oldest retained sample, and the read model can tell that the space before the series once
held data. Recording the survivor instead would put the mark exactly where the series begins
and the gap test could never fire.

The mark only rises. A gap it produces is labelled `NOT_RETAINED` — *we* deleted that, which
is a different fact from *nobody was watching*.

The session's start level is never lost, because it lives on the start **snapshot**, and
snapshots are not retention-managed.

### Counter eviction: three comparisons

A capture between `prev` and `next` may be removed only when **all three** are comparable:

```
A = comparability(prev, candidate)     an existing interval
B = comparability(candidate, next)     an existing interval
C = comparability(prev, next)          the adjacency deletion would create
```

A and B protect discontinuities already known. C stops a clean-looking one being manufactured.

The rule started as C alone, and that permits exactly the deletion it was written to prevent:

```
prev = 100    candidate = 50    next = 120
```

`prev → candidate` is a counter decrease and is refused — but `prev → next` reads 100 → 120
and computes a clean **+20**, a number that looks like a measurement and spans a counter reset.
Metadata behaves the same way whenever a value round-trips (boot `b1→b2→b1`, generation
`3→4→3`, elapsed `0→5→2`): the refusal never reaches C.

Nothing is special-cased by reason. The rule consumes whatever `comparability` returns, so a
reason added later is covered without touching it. **No verdict is persisted** — every
comparison is recomputed from stored observations, which is what keeps it correctable.

The baseline and the newest capture are never candidates.

## Identity interning

Partial wakelock names average **79 characters** and reach **423** — they are call chains, not
labels. With 408 partial rows per capture, roughly 32 KB of identical text was being rewritten
every time. `wakelock_identity` stores each `(family, uid, name)` once; counter rows reference
an integer.

Measured against the shipped schema, like for like:

| retained captures | repeated text | interned | reduction |
|---:|---:|---:|---:|
| 8 (the target) | 856 KB | 368 KB | **2.33×** |
| 24 | 2456 KB | 784 KB | **3.13×** |
| 288 | 29440 KB | 8216 KB | 3.58× |

Per capture at the retention target: **107 KB → 46 KB.** Every alternative considered (sparse
checkpoints, interval deltas, top-N) bought less by throwing information away.

An earlier figure of 4.15× was withdrawn in Phase 9B.1: the prototypes it came from did not
carry the same indices, so part of that saving was index removal rather than interning, and it
was measured at 288 captures — an operating point the retention target makes unreachable.

`AUTOINCREMENT` is load-bearing: identities are swept, and without it SQLite would reuse a
deleted rowid and silently relabel a different wakelock.

**One index, chosen by measurement.** The counter tables carry `INDEX(identity_id)` and nothing
else. A separate `INDEX(capture_id)` was removed in Phase 9B.1: the primary key is
`(capture_id, accounting_window, identity_id)`, so SQLite already serves the by-capture query
from the primary-key index with the same plan and the same measured time, while the extra index
cost 17–20% of these tables. The identity index was narrowed from `(identity_id, capture_id)`
to `(identity_id)` for the same reason — no slower at any retained size, 8–14% smaller. Dropping
it entirely was measured too and rejected: the planner falls back to a skip-scan and the
identity-series query becomes 1.2–1.7× slower.

### This dictionary is not harmless metadata

Measured on the same capture: **60.3% of partial wakelock names contain a dotted package-style
token**, and **63 distinct package prefixes** are recoverable from the names alone —
`com.google.android.apps.messaging.shared.receiver.bootcomplete`, and so on.

So it is, in effect, an inventory of what runs on the device: the thing Phase 7B declined to
persist when it decided not to store package mappings. Interning does not introduce that data —
v2 already held it — but it changes its **lifetime**, from "as long as a counter row references
it" to "forever, unless swept".

So it is swept, transactionally, with **every** retention pass and every clear, not only when
the user wipes everything. During the Phase 9A measurements an identity appeared within minutes
of installing an application: `(uid 10237, "*launch*")` — BattInsight itself.

## Segments and gaps

The read model hands Phase 9C **segments and gaps**, never a flat list of points. Every
line-chart renderer connects consecutive points by default, and every gap here means
something.

| reason | what it says |
|---|---|
| `NOT_OBSERVED` | nobody was sampling — the app was not visible |
| `PROCESS_RESTART` | the process died; the next sample announced itself as a fresh start |
| `DIFFERENT_BOOT` | the device rebooted, so there is no shared axis at all |
| `CONTINUITY_UNPROVEN` | continuity could not be proven **either way** |
| `NOT_RETAINED` | samples existed and retention deleted them — ours, not the device's |
| `MALFORMED` | stored state contradicts itself |

Guarantees the builder makes, so the chart cannot break them: a gap is never a point and never
a zero; two segments are never adjacent without a gap between them; a segment never spans two
boots; a one-point segment is legal and renders as a point.

### Boot comparison has exactly one implementation

`BootIdentity.relationTo`, and nothing else.

```
Kernel(a) vs Kernel(a)   SAME
Kernel(a) vs Kernel(b)   DIFFERENT
anything else            UNKNOWN     -- including Derived(x) vs Derived(x)
```

Two equal *derived* values are still `UNKNOWN`, because a derived value is an estimate rather
than evidence. A second comparison written by hand against the stored fields would say `SAME`
there and quietly draw a line across a reboot.

### A wall-clock jump is not a gap

A clock correction — NTP, a timezone change — moves the wall clock without any elapsed time
passing. It changes the labels on the axis and nothing about whether two readings connect.
Ordering and arithmetic are always elapsed realtime; wall clock is display only.

## Two counter readings, kept apart

| question | computed from | used by |
|---|---|---|
| "What accumulated during this session?" | **baseline → latest** | Phase 8 detail screen |
| "When did activity increase?" | **capture N → N+1** | Phase 9C |

Deriving the second from the first is the mistake the separation prevents: every baseline→N
interval includes everything before it, so a chart of them is monotonically non-decreasing *by
construction*. It would look like a trend and be an artefact of the arithmetic.

Each adjacent interval is evaluated **independently**. A refusal at N → N+1 says nothing about
N+1 → N+2. A refusal never advances `CounterGeneration` and never claims the system reset its
counters — a chart is not evidence.

## Truth rules

Unchanged from earlier phases, and now also enforced by the series:

```
missing        != zero
unknown        != zero
unavailable    != zero
not comparable != zero

COUNTER_DECREASED != negative, and != zero
different boot    != continuous series
UNKNOWN boot      != continuity
recovery          != observed transition
wall-clock jump   != elapsed-duration jump

no interpolation across an unobserved or refused interval
```

## Not here

No charts. No background collection. No automatic privileged capture — `dumpsys batterystats`
runs only when a person presses refresh. No package-mapping persistence. No network.
