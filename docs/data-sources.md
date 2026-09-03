# Data sources

## Acquisition formats

Both structured formats were measured working on Android 10 and Android 16, from an ADB
shell, from an app UID holding the three required permissions, and through a Shizuku shell
session. All three backends produced structurally equivalent output.

| Format | Argument | Size (A16) | Speed | Role |
|---|---|---|---|---|
| **PROTO** | `--proto` | 91 KB | fastest | Aggregate. Typed. Candidate for routine snapshots |
| **CHECKIN** | `-c` | 818 KB | ~2× slower | Aggregate **and** history. Documented and CTS-covered |
| **TEXT** | *(none)* | 2.5–3.1 MB | slowest | Human reading only. **Not a parser input** |

**No format is designated primary.** Phase 1A favoured CHECKIN on documentation and
CTS-coverage grounds; Phase 1B then measured PROTO at roughly one ninth the size and about
twice the speed. The likely eventual split — PROTO for routine snapshots, CHECKIN for
history and diagnostics — is **not yet decided** and is deliberately not encoded in
`SourceFormat`. It requires version-matched protobuf decoding, which has not been done.

### Two framing traps

- `--proto` is length-delimited; `--proto --history` is the bare message. A decoder that
  assumes one framing will read the other as corruption.
- The checkin `vers` record and the protobuf's first two fields carry the *same* schema
  version. Gate on exact values: the parcel version moved 1310906 → 215 between Android 10
  and 16, so any range or magnitude comparison breaks.

## Prohibited commands

`FoundationContractsTest` asserts none of these can appear in a `SourceFormat` argument.

| Command | Why prohibited |
|---|---|
| `--checkin` | The platform's own help states it "will write (and clear) the last old completed stats when they had been reset". **Use `-c`**, which only writes current stats and returns more data |
| `--reset`, `--reset-all` | Destroys the user's statistics |
| `--write`, `--new-daily`, `--read-daily` | State-changing |
| `enable full-history`, `no-auto-reset`, `pretend-screen-off` | State-changing |
| `settings put global hidden_api_policy` | Device-wide security downgrade affecting every installed app. **Never** |

## Kernel wakelocks

Reachable through battery statistics; **not** through the filesystem.

| Source | Result |
|---|---|
| batterystats `kwl` tag | ✅ Works on both backends, no root, no debugfs |
| `/sys/kernel/debug/wakeup_sources` | ❌ debugfs unmounted on Android 12+ user builds |
| `/sys/class/wakeup/*` | ❌ Exists on Android 16 at mode 0755 root:root but SELinux-denied to the shell domain |
| `/proc/wakelocks` | ❌ Long removed from the kernel |

The archived predecessor's sysfs collectors have no role. Their data sources were
unreadable on every environment tested.

## UID to package name resolution — open design question

**Do not import the predecessor's resolver, and do not add `QUERY_ALL_PACKAGES` to make
this go away.**

Measured: an app UID holding all three permissions saw the **same 98 UIDs** as an ADB shell
but only **152 of 180** UID-to-package-name mapping lines. A Shizuku shell session matched
the shell exactly. This is package-visibility filtering, not a formatting problem, and it
maps onto a real user-visible defect in the predecessor: shared-UID processes showing
misleading labels.

The investigation, deferred to a later phase, must cover:

- `PackageManager.getPackagesForUid` and shared-UID semantics
- `<queries>` declarations and targeted package visibility
- Launcher-intent visibility, which the predecessor used deliberately instead of
  `QUERY_ALL_PACKAGES` — the right instinct
- Whether `QUERY_ALL_PACKAGES` is genuinely required, and its Play policy implications
- Whether the Shizuku backend should perform resolution when available, given it is not
  subject to the filtering

Until resolved, resolution quality is a capability in its own right — hence
`CapabilityState.AvailableDegraded`.

## Battery properties

`BatteryManager` and the `ACTION_BATTERY_CHANGED` sticky intent supply level, scale,
status, health, plugged, present, technology, temperature, voltage, and five of six
`BATTERY_PROPERTY_*` values (`ENERGY_COUNTER` returned a sentinel) — **with no permission
at all**.

sysfs battery nodes are not a viable baseline: every node was permission-denied on the one
physical device measured, and `charge_full_design` — required for a health percentage —
did not exist on the emulator. Advanced battery health remains speculative and low priority.

---

## What the capability layer probes today

Acquisition is verified behaviourally, not assumed from permissions:

| Probe | Command | What it establishes |
|---|---|---|
| Battery statistics | `dumpsys batterystats --proto` | Whether acquisition works at all |
| Kernel wakelocks | `dumpsys batterystats -c` | Whether `kwl` records exist, and whether any carry values |

Protobuf is the routine probe because it was measured at roughly one ninth the size of
checkin and about twice as fast. Validation is **structural only** — the length-delimited
framing is checked and the payload discarded. No production decoder exists yet, and a
non-empty protobuf establishes only that *acquisition* works, never that every field is
populated.

Checkin is used solely for kernel wakelocks, where the `kwl` records are greppable as text
and reading them from protobuf would require the decoder that is deliberately deferred. The
payload is scanned within a bounded prefix and then discarded.

The retired sysfs sources — `/proc/wakelocks`, `/sys/kernel/debug/wakeup_sources`,
`/sys/class/wakeup` — are **not** used and are not resurrected. Measurement retired them.
