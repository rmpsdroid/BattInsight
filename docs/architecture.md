# Architecture

> Phase 2A. Contracts only. No layer below the capability boundary is implemented.

## Layering

Six layers, ordered so that a change in how data is *collected* can never corrupt data
already *stored*. That single property is what the predecessor lacked, and it is why an
upgrade there once deleted every user's history.

| Layer | Responsibility | State in Phase 2A |
|---|---|---|
| **Collection** | Obtain raw bytes and report execution mechanics. No interpretation, no policy | `PrivilegeBackend`, `BackendIdentity`, `SourceFormat`, `CollectionResult`, `CollectionOutcome` — contracts only |
| **Capability** | Decide what an outcome *means* for a given source | `Capability`, `CapabilityState`, `SourceReading`, `CapabilityInterpreter` |
| **Domain** | Normalise raw output into stable value types | Not started |
| **Session engine** | Snapshot identity, session boundaries, comparability, reconciliation | Not started. See `session-model.md` |
| **Persistence** | Store snapshots durably with explicit schema versioning | Not started |
| **Presentation** | Screens, chart models, reports | Capability Centre (Compose) |

Packages exist only where they hold real code. Empty packages were not created to complete
a diagram.

## Why the collection boundary is an interface

Two backends are planned and both were measured producing structurally identical output —
same version record, same 46 record tags, same 121 visible UIDs, same kernel wakelock count:

- **Granted app backend** — our own process, holding three permissions. No third-party app.
- **Shizuku shell backend** — measured at uid 2000, `u:r:shell:s0`. Needs none of our
  privileged permissions, runs 2–4× faster, and resolves UID names the app UID cannot.

Because their outputs are equivalent, one interface is honest rather than merely tidy.
Neither is implemented yet; the boundary is fixed first so the session engine can be built
and tested against a fake.

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
| Room schema | Phase 6 | No domain model to persist yet |
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

Execution enforces a timeout, honours cancellation, captures stdout and stderr separately,
and records a nullable exit code — nullable because a process that never completed has no
exit status, and conflating that with "exited 0" is the mistake the whole architecture
exists to avoid. Captured output is bounded; payloads are never logged.
