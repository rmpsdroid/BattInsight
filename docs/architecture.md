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
| **Presentation** | Screens, chart models, reports | Capability Centre, onboarding and Manage access (Compose) |

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

## Why the session engine is pure

`SessionEngine` has no Android import, no clock, no I/O and no randomness beyond an
identifier factory it is handed. Everything platform-shaped lives in `AndroidBatterySource`,
which maps intent extras to `BatteryObservation` and holds no session logic at all.

That split is what makes roughly eighty lifecycle scenarios — reboots, process death,
stale broadcasts, wall-clock jumps, contradictory battery states — run on the JVM in
milliseconds. Each is a function of its arguments, so a failure names one cause rather than
a race.

`SessionCoordinator` sequences observations and publishes state. It owns no decisions.

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
