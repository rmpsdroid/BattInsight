# Architecture

> Phase 2A. Contracts only. No layer below the capability boundary is implemented.

## Layering

Six layers, ordered so that a change in how data is *collected* can never corrupt data
already *stored*. That single property is what the predecessor lacked, and it is why an
upgrade there once deleted every user's history.

| Layer | Responsibility | State in Phase 2A |
|---|---|---|
| **Collection** | Obtain raw bytes from a source. No interpretation, no policy | `PrivilegeBackend`, `BackendIdentity`, `SourceFormat`, `CollectionResult` — contracts only |
| **Capability** | Determine what is possible *right now* by attempting operations | `Capability`, `CapabilityState` — model only |
| **Domain** | Normalise raw output into stable value types | Not started |
| **Session engine** | Snapshot identity, session boundaries, comparability, reconciliation | Not started. See `session-model.md` |
| **Persistence** | Store snapshots durably with explicit schema versioning | Not started |
| **Presentation** | Screens, chart models, reports | Placeholder activity only |

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
2. **Classification is by content, never by exit code.** Every denial measured returned
   exit 0 with the error on stdout.
3. **Empty is not failure.** See `capabilities.md`.
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
