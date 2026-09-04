# Persistence

How BattInsight stores battery sessions, what it promises about them, and four things it
deliberately does not do yet.

Phase 5 built the session engine as pure Kotlin with a `SessionStateStore` seam and no
implementation behind it. Phase 6 fills that seam with Room. Nothing about the engine's rules
changed; what changed is that its conclusions now outlive the process.

## What is stored

Three tables, in `battinsight-sessions.db`:

| Table | Holds | Rows |
|---|---|---|
| `battery_snapshots` | one immutable battery reading | many |
| `battery_sessions` | one interval, referencing its snapshots | many |
| `engine_state` | what the engine currently believes | exactly one |

Every field is an explicit typed column. The predecessor application stored its snapshots as
one serialized blob with no version, and an update destroyed every user's history -- there was
nothing to migrate because there was no schema. Typed columns can be inspected, diffed,
migrated and argued about.

Enums are stored **by name**, never by ordinal. Reordering an enum would silently reinterpret
stored history; an unrecognised name fails loudly instead, as `MAPPING_FAILURE`.

Nothing here is a battery *statistic*. These are BattInsight's own observations of the public
`ACTION_BATTERY_CHANGED` reading. Decoded `batterystats` data has no tables yet, and will not
get speculative ones -- a schema committed before the shape of the data is known is a
migration waiting to happen.

## Atomicity, and a foreign-key cycle that had to be broken

A session boundary is several durable facts at once: an interval ends, another begins, their
snapshots appear, and the engine state moves. If a crash could land between them the database
would hold two active sessions, or none, or an engine state naming a row that was never
written. So a transition is one `@Transaction`.

The natural schema has a reference cycle -- sessions name their snapshots, snapshots name
their session -- which no insert order satisfies under immediate constraints. The first
attempt deferred the checks to commit time, which makes the cycle expressible.

**That was measured and abandoned.** A deferred violation inside a Room `@Transaction` threw
`SQLiteConstraintException` *and left the offending rows committed*: one session row and the
engine-state row survived a write that had failed.

```
before: PROBE threw=SQLiteConstraintException sessions=1 snapshots=0 engineState=EngineStateEntity(...)
after:  PROBE threw=SQLiteConstraintException sessions=0 snapshots=0 engineState=null
```

That is the exact partial commit the schema exists to prevent, wearing integrity's clothes.
The cycle is broken instead: `battery_snapshots.session_id` keeps its column and index but
carries no foreign key, so the remaining graph has a topological order -- snapshots, then
sessions, then engine state -- and every constraint is immediate.

The direction that was kept is the one that matters. A stored session can always be rebuilt
because the snapshots it names are guaranteed to exist. An orphaned snapshot costs one row
and loses nothing.

`PRAGMA foreign_keys = 1` and `PRAGMA defer_foreign_keys = 0` are asserted on a real
Android 16 device by `PersistenceMigrationTest`, not only under Robolectric.

## Writes update rows; they do not replace them

The DAO uses `@Upsert`. That is a correctness choice, and it replaced something that was
measurably wrong.

The writes began as `@Insert(onConflict = REPLACE)`, which Room compiles to SQLite's
`INSERT OR REPLACE`. SQLite resolves a primary-key collision there by **deleting** the
existing row and inserting a new one. Measured on the session row while advancing its
`latest_snapshot_id` -- the most ordinary write this application performs, on every accepted
observation:

```
INSERT OR REPLACE:  rowidBefore=1 rowidAfter=2 identityPreserved=false
@Upsert:            rowidBefore=1 rowidAfter=1 identityPreserved=true
```

Nothing was lost, and that is the uncomfortable part. It survived only because every foreign
key here is `NO_ACTION` and the reinsert lands inside the same statement, so no immediate
constraint is violated at statement end. That is a coincidence of today's schema rather than a
property of the operation: the first child table declared with `ON DELETE CASCADE` would have
had its rows silently deleted by what reads as a field update.

`UpsertIdentityTest` asserts row identity directly rather than only checking contents
afterwards, because a contents check passes under either implementation. Restoring
`INSERT OR REPLACE` fails exactly three of those tests, which is what makes them evidence.

## Failure is typed, and never silent

`PersistenceOutcome` names what went wrong -- `CONSTRAINT_FAILURE`, `MAPPING_FAILURE`,
`DATABASE_UNAVAILABLE`, `CORRUPT_STATE`, `MIGRATION_FAILURE`, `UNKNOWN` -- in the pure session
package, with no SQL vocabulary in it.

Two rules follow from it:

**A load distinguishes "nothing" from "could not tell."** `StoredState` is `Loaded`, `Empty`
or `Failed`. Treating an unreadable store as empty would start a fresh interval and quietly
discard whatever was there. The UI says *"Earlier history could not be read, so this session
starts fresh. Nothing has been deleted."* rather than implying there was no history.

**A transition is not adopted unless it was stored.** `SessionCoordinator.commit` persists
first, adopts second, publishes third. A failed write leaves the previous state in force and
reports the failure. The alternative -- carrying on in memory -- produces an application
confidently describing history it will not have after the next process death.

### A closed database, and a workaround that no longer exists

Room 3 reports a closed database as `IllegalStateException("Database is closed")`, measured
on 3.0.2, from both the plain DAO path and the transaction path. That is an ordinary
documented failure, so the store simply classifies it as `DATABASE_UNAVAILABLE`.

This is worth recording because Room 2.8.4 did neither of those things, and the code carried
a workaround for it. Under Room 2 a plain DAO call after `close()` threw
`JobCancellationException` from Room's own internal coroutine scope, so the obvious handler
-- rethrow every `CancellationException`, which structured concurrency otherwise demands --
would have cancelled the session coordinator *because a database went away*. The store had to
ask whether the **caller's** context was still active to tell the two apart. Worse, a
`@Transaction` did not fail at all: Room reopened the database underneath it and wrote into
the reopened one.

Both behaviours are gone in Room 3, so the special-case handling was deleted rather than
carried forward. What remains is the plain rule: a `CancellationException` is the caller's
and is rethrown.

## Versioning

Four version domains move independently and must never be compared with one another:

| Version | Means | Lives |
|---|---|---|
| Room database version | the shape of these tables | `BattInsightDatabase.DATABASE_VERSION` |
| Snapshot schema version | the shape of the domain snapshot | a column on every row |
| Android platform version | the OS at capture | a column on every row |
| `batterystats` parcel version | Android's own counter format | not stored yet |

Phase 1A measured Android's parcel version going *down* between platforms -- 1310906 on
Android 10, 215 on Android 16 -- so anything reasoning across domains by magnitude is already
wrong.

### No destructive migration, ever

`Room.databaseBuilder` never calls `fallbackToDestructiveMigration()`. A schema change is not
permission to delete a user's measurements. If a migration cannot be performed, Room throws on
open and the store reports `MIGRATION_FAILURE`; the data stays on disk.

This is enforced by `PersistencePolicyTest`, which fails if the call appears anywhere in
production source, along with the rules that every shipped version has an exported schema and
that the `session` package imports no Room, SQLite or Android types.

Exported schemas live in `app/schemas/` and are committed. A schema that exists only as build
output cannot be diffed in review, and a migration written against a schema nobody can see is
a migration nobody can check.

`PersistenceMigrationTest` stands the Room `MigrationTestHelper` up on a real device now,
while there is nothing to migrate. A harness written for the first time alongside the first
migration is a harness whose own correctness is unproven at the moment it matters most, and
any failure then would be ambiguous between a bad migration and a bad test.

## Process death

The claim that a session survives its process cannot be made on the JVM: an in-memory
database that a test closes and reopens has not survived anything.

`tools/process-death-proof.sh` runs `ProcessDeathRecoveryTest` as **two separate
instrumentation invocations** with `am force-stop` between them, on the Pixel_8 emulator.
Instrumentation runs inside the application's own process, so the kill is real. Each half
logs its pid and the harness requires the two to differ -- otherwise a surviving process with
a warm Room singleton could pass while demonstrating nothing.

```
== step 1: create and store a session ==
   stored session: 81eb783e-af70-401b-b3e9-70887fb36357 (written by pid 5285)
== step 2: kill the process that created it ==
== step 3: a new process must recover the same session ==
   recovered by pid 5336

PROVED: session 81eb783e-af70-401b-b3e9-70887fb36357 was written by pid 5285
        and recovered by pid 5336.
```

## Four things deliberately not done

### 1. No manifest-declared power receiver

BattInsight observes power transitions through a receiver registered at runtime
(`RECEIVER_NOT_EXPORTED`) for as long as something is collecting. It could instead declare a
manifest receiver for `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` and record
boundaries while closed.

Evaluated:

1. **Would it improve accuracy?** Yes, for boundary *timestamps*. Today a plug event that
   happens while the app is closed is inferred at next start-up from the reading then, so the
   recorded boundary is late by however long the app was closed.
2. **Is the loss recoverable without it?** Partly. Cold-start reconciliation already detects
   *that* a transition happened and marks it inferred rather than witnessed. What is lost is
   *when*, not *whether*.
3. **Do these broadcasts reach manifest receivers at all?** `ACTION_POWER_CONNECTED` and
   `ACTION_POWER_DISCONNECTED` are on the platform's implicit-broadcast exception list, so
   yes. `ACTION_BATTERY_CHANGED` is not, and cannot be received from a manifest component at
   all -- so a manifest receiver would give boundaries only, never levels. *Documented
   platform behaviour; not measured here, because the decision below does not depend on it.*
4. **What does it cost the user?** A process start on every plug and unplug, for the life of
   the install, whether or not they are using the application.
5. **What does it cost in battery terms?** Small but not nothing -- and a battery diagnostics
   tool that wakes the device to record that the device woke is in an awkward position.
6. **Does it need a new permission?** No.
7. **Does it widen the attack surface?** Marginally: a manifest receiver is a component that
   exists whether or not the app is running. It would be `exported="false"`.
8. **Does it interact with background restrictions?** Yes, and unpredictably. A user who
   restricts the app, or an OEM that restricts it for them, gets silently degraded data -- and
   BattInsight would have no way to tell that from "no transitions happened", which is exactly
   the class of quiet wrongness this project exists to avoid.
9. **Is the data currently useful?** Not yet. There is no history screen. Nothing consumes
   precise boundary timestamps.
10. **Is it reversible?** Yes, cheaply. The schema does not change; only when rows are
    written.

**Decision: do not add it now.** It buys timestamp precision for data nothing yet displays, at
the cost of a permanent background component and a new silent-degradation mode. Reconsider
when the history screen exists and the imprecision is visible -- at which point the honest
version records boundaries *and* marks them background-observed, so the difference between
witnessed and inferred stays in the data.

### 2. No `RECEIVE_BOOT_COMPLETED`

Room does not need it. Boot identity is established by reading
`/proc/sys/kernel/random/boot_id` when the application next runs, which is measured to work
from an ordinary app on Android 16 and needs no permission or background execution.

The permission would only let BattInsight start itself at boot to notice a reboot sooner. It
would notice the same reboot, with the same certainty, whenever it next opens. A diagnostics
tool that launches itself on every boot to learn something it could learn later is asking for
a real cost for no user-visible benefit.

**Decision: do not add.**

### 3. No event log or journal table

Considered: an append-only table of every transition, as an audit trail independent of the
current state.

It is attractive for debugging and would make some future questions answerable. But it is
storage with no reader: nothing in the application consumes it, its schema would be guessed
rather than derived, and an append-only table of battery events on a device is a growth
problem that needs a retention policy -- a policy that would also have to be guessed.

The session table already *is* a durable record of every interval, including closed ones with
their end reasons. That covers the questions currently worth asking.

**Decision: do not add now.** Revisit if a concrete diagnostic question turns up that the
session table cannot answer.

### 4. No "Clear session history" action yet

`SessionStateStore.clear()` exists and is tested. What does not exist is a button for it, and
that is a decision rather than an omission.

Storing a user's data without a way to remove it is a real gap, so this was close. Two things
decided it:

- **There is nothing to see being cleared.** Without a history screen, the only visible effect
  is the "Saved on this device" count. Clearing takes it to zero.
- **And it does not stay at zero.** Clearing removes stored history including the engine-state
  row, but the *current* interval is still live in memory, so the next battery broadcast
  writes it back. The count returns to one session almost immediately. That is correct
  behaviour -- the current interval is not history, and a user clearing history is not asking
  to stop measuring -- but presented as a bare counter it reads as a button that did not work.

Shipping a control whose visible effect is "the number goes to zero and then back to one" is
worse than not shipping it.

**Decision: defer to the phase that adds the history screen**, where the action has something
to act on visibly, and where the copy can say plainly that the interval in progress is kept.
Until then nothing is stored that the user cannot remove by clearing the app's data or
uninstalling, and neither is hidden from them.

## Retention

Session and snapshot history is **intentionally retained indefinitely for now**. Nothing
deletes it on a schedule, and `battery_snapshots` therefore grows for as long as BattInsight
keeps recording observations.

That is a deliberate position rather than an oversight. Retention controls are deferred until
the history and export requirements are known, because a retention policy written before
anyone can see the data is a policy that decides what to destroy without knowing what it is
worth. There is no background job, and no automatic deletion.

The user can still remove everything by clearing the application's data or uninstalling it.

## What is not stored, and will not be

No package names, no per-application usage, no identifiers beyond BattInsight's own UUIDs and
the kernel boot id, and no network anything. `storageCounts` reports *how many* rows exist and
never their contents. See [security-privacy.md](security-privacy.md).
