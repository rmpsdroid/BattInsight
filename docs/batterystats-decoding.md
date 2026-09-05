# Batterystats decoding

How BattInsight turns acquired bytes into a model it is willing to show a user, and what it
deliberately refuses to guess at.

## The source format

**`dumpsys batterystats -c`. Primary and, for now, the only implemented decoder.**

Phase 1A measured both machine-readable formats on Android 10 and Android 16 and chose
checkin provisionally, pending re-evaluation before any decoder was written. Phase 7A did that
re-evaluation against the thing Phase 1A could not weigh: what it costs to actually decode
each one.

| | CHECKIN (`-c`) | PROTO (`--proto`) |
|---|---|---|
| Documented as machine-readable | yes, with a section-identifier table | no |
| CTS coverage | the format, via a host-side test | not established |
| | *see [what CTS covers](#what-cts-actually-covers)* | |
| Schema needed to read it | none | AOSP `.proto`, per version |
| Licensing cost | none | vendoring Apache-2.0 schema into a GPL-3.0-only repo |
| Payload size | 803–872 KB | 72–90 KB |
| Fixtures | text; diffable and reviewable in a pull request | binary; unreadable without the schema |
| Unknown-field tolerance | unknown tags skip | unknown fields skip |
| Version block | `vers`, four fields | first two fields, same values |

Size favours proto by roughly 9x and Phase 1A measured it faster. That is a real advantage and
it did not win, for two reasons.

The first is licensing, and it is a hard gate rather than a preference. Decoding proto in
production means the AOSP schema, which means vendoring Apache-2.0 files into a GPL-3.0-only
repository and keeping per-file provenance for generated code. Phase 0 requires that decision
to be made explicitly by the owner, not absorbed into an implementation phase. See
[Licensing](#licensing-and-why-proto-is-deferred-rather-than-rejected).

The second is that a checkin fixture is text. A project whose central failure mode is
undiagnosable data loss benefits from evidence a human can read in a bug report, diff in a
pull request, and check by eye against the device. Every test in this phase asserts against
lines anyone can see.

### What CTS actually covers

Worth stating precisely, because the loose version of this claim is wrong. CTS's
`BatteryStatsDumpsysTest` exercises `dumpsys batterystats --checkin`. That covers the
**checkin format** — the aggregate record grammar this decoder parses — and it is what binds
OEMs to keep that grammar well-formed.

It does not cover the `-c` stream. `-c` returns the same aggregate records plus an interleaved
history block, and that interleaving is not what the CTS test drives. Nothing external
validates it; BattInsight's own real-capture tests do, by asserting exact history-block line
counts on both measured platforms and pinning the two ways history was previously mistaken for
aggregate records.

**Plain text output stays rejected as a parser input.** It exposes no capability the
structured formats lack, and costs 2.5–3.1 MB.

**`--checkin` is never used.** Both devices' own `--help` documents it as writing *and
clearing* the last old completed stats. `-c` has no such documented effect and returns more —
aggregate *and* history in one call.

## What is decoded

Four record types. Android 16 emits **46 distinct aggregate record types**, so 42 are left
undecoded and counted:

| Tag | Meaning | Source of the layout |
|---|---|---|
| `vers` | format and platform versions | AOSP constants + both captures |
| `uid` | numeric UID to package name | both captures |
| `kwl` | kernel wakelocks | AOSP `KERNEL_WAKELOCK_DATA` + both captures |
| `wl` | per-UID wakelocks; the partial block | AOSP's verbatim layout comment |

Everything else is counted in `unsupportedTags` and reported, never modelled. An empty typed
structure invites code that reads it and believes the zeros; a count says plainly that
BattInsight has not learned to read this yet.

A tag graduates to decoded when its field layout has been read out of AOSP source — not when
it looks obvious. Guessing units from magnitudes is exactly how a battery tool ends up
confidently displaying milliseconds as seconds.

## Counter semantics

| Field | Unit | Kind | Zero | Missing |
|---|---|---|---|---|
| `kwl` total time | milliseconds | cumulative within the window | real: lock never taken | tag absent = not reported |
| `kwl` count | unitless | cumulative | real | as above |
| `wl` partial total time | milliseconds | cumulative within the window | real | record absent |
| `wl` partial count | unitless | cumulative | real | record absent |

Times are milliseconds because AOSP rounds microseconds to milliseconds when writing checkin,
not because the magnitudes look like milliseconds.

Cumulative counters only increase within one accounting window. A decrease means the window
restarted — which is *evidence toward* a counter reset, not proof of one. See
[Counter generation](#counter-generation-stays-deferred).

Negative values are refused rather than stored: a negative cumulative counter is not a small
number, it is a broken record.

**Missing is never zero.** Four states are kept apart, because collapsing them is how a tool
tells a user something false:

- the tag was absent — the device did not report this at all
- the tag was present with no records
- records were present with zero values — a real measurement
- the source was malformed — we could not read it

## Two record layouts worth writing down

**The `wl` marker letter is second, not first.** AOSP's layout is
`full totalTime, 'f', count, current, max, total, partial totalTime, 'p', count, …`. A parser
that expects the marker to lead reads the previous block's total as this block's marker and
produces plausible numbers from the wrong column — the worst kind of wrong, because nothing
looks broken.

**`-c` contains two formats, and the history block has two line shapes.** Aggregate records
are `9,<uid>,<window>,<tag>,…`. The history block is `9,h,<elapsed>,<events…>` for events and
`9,hsp,<index>,<uid>,"<string>"` for the string pool they reference. In both, the field that
holds a record tag in an aggregate line holds something else entirely.

Three separate defects came out of this one difference, each found by testing against the
production format rather than the more convenient `--checkin`:

1. Dispatching on field 3 without recognising `h` turned every event fragment (`+r`, `-w`,
   `Bl=100`) into a fictional record type — 51,639 of them in one capture.
2. Checking minimum length *before* the history check rejected three-field history lines such
   as `9,h,0:RESET:TIME:1788344548223` as truncated records, undercounting history by 2,746
   and 7,080 lines and emitting that many spurious warnings.
3. Missing `hsp` did the same thing again, and worse: `hsp` puts a **UID** where an aggregate
   record puts its tag, so every distinct UID in the string pool became a record type. The
   reported count of undecoded record types was 124 against 42 real ones, and that wrong
   number reached the diagnostic screen.

The history markers are an explicit set, `{"h", "hsp"}`, because those are the only two
non-numeric values field 1 takes across all four measured captures.

## Version gating

The payload's own version record gates every counter. Platform API level is a different domain
and is never used to infer layout.

```
A10:  9,0,i,vers,34,1310906,QP1A.190711.020,QP1A.190711.020
A16:  9,0,i,vers,36,215,BE2A.250530.026.D1,BE2A.250530.026.D1
```

Gate on exact values, never on ranges. Between Android 10 and 16 the **parcel version went
down**, 1310906 to 215. Any parser comparing magnitudes is already wrong, and a test pins that
fact against both captures.

Checkin versions 34 and 36 are verified against real captures. An unlisted version still
decodes — refusing would make every future Android release a failure — but carries an
`UNVERIFIED_VERSION` warning, because the layout is then assumed rather than measured.

A window whose start and end build fingerprints differ spans an OS update, and counters from
either side of it are not comparable. That is warned about too.

## Denial, truncation, and other honest failures

`DecodeOutcome` is typed, and exception-or-not is not the contract.

**A denial is never an empty capture.** `dumpsys` was measured returning **exit status 0** with
the denial on stdout and an empty stderr, so anything keying off the exit code sees success.
Classification goes through the existing collection layer rather than a second implementation,
because two independently written interpretations is how one application ends up disagreeing
with itself about whether the user has access.

The denial scan is bounded to the first 4 KB. Denials are short and arrive first; scanning a
whole 800 KB capture for the word "SecurityException" would let any application name a
wakelock that and make the user's capture unreadable.

**A truncated capture can never succeed.** Phase 3.1 found a real defect where reading only
the first 512 KB missed kernel wakelocks entirely, because the `kwl` block sits at 84–88% of
the payload. A prefix that parses perfectly is still a prefix, and the sections after the cut
are *missing*, not absent. Every truncated prefix of a valid capture is tested, byte by byte,
to confirm none of them can come back as a success.

## UID and package mapping

The numeric UID is the identity. The package name is optional enrichment.

Phase 3 measured that Shizuku resolves more package names than the application's own UID can,
because package-visibility filtering applies to an ordinary app and not to the shell. A UID
with no mapping is therefore normal, and **statistics are never discarded for want of a name**
— doing so would silently delete real data on precisely the backend that is most restricted.

## Backend independence

The same bytes decode identically whether Shizuku or the granted-app backend produced them.
The backend reaches the decoder only as `CaptureMetadata.backendKind`, for diagnostics, and is
never branched on. Choosing a backend remains Phase 4's job.

## Counter generation stays deferred

Phase 5 created `CounterGeneration` with no production reset detector, and Phase 7A does not
add one.

Decoding makes reset detection *possible* to think about — a cumulative counter that decreases
is evidence — but not yet sound. A decrease also follows from a new accounting window, a
platform update inside the window, or a record disappearing for unrelated reasons. Advancing a
generation on that evidence would invalidate comparisons that were actually fine.

What Phase 7A contributes toward a future detector: the version block, the accounting window on
every statistic, and the platform-change flag. What it does not do is guess.

## Session boundaries are untouched

Decoded counters attach to a capture. They do not own session identity and are never used to
infer a charging transition. These stay distinct:

```
session boundary  ≠  counter generation  ≠  batterystats reset
                  ≠  process restart     ≠  reboot
```

## What is stored, from Phase 7B

The raw payload is still never stored. Two decoded counter families are: kernel wakelocks and
per-UID partial wakelocks, with the version and platform metadata needed to refuse an unsafe
comparison. Package names are not persisted.

Schema version 2, migrated additively from version 1 and bounded to one baseline and one
latest capture per battery session. See [persistence.md](persistence.md).

Nothing else survives a capture: not the 42 undecoded record types, not the 45,000 history
lines, not the payload.

## Licensing, and why proto is deferred rather than rejected

Proto is not rejected on merit. It is better shaped — typed fields, no CSV quoting ambiguity,
a tenth of the bytes — and Phase 1A confirmed it present on Android 16.

It is deferred because production decoding needs AOSP's `.proto` schema, and importing it is a
provenance decision this phase is not authorised to make alone. Phase 0 requires per-file
provenance to be explicit, and the questions to answer first are:

- exact source files and AOSP revision or tag
- license header and copyright holder
- whether modification is needed
- attribution and NOTICE requirements for a GPL-3.0-only project
- what the generated code inherits

**No AOSP schema file was copied into this repository.** No generated protobuf code exists.
The `SourceFormat.PROTO` enum value remains, and `BatteryStatsProbe` still identifies proto
payloads structurally for capability purposes without decoding their fields.

A schema-free proto decoder is possible in principle — protobuf wire format is
self-describing enough to walk field numbers and types, which is how Phase 1A verified the
version block without any schema. But field *numbers* without a schema are not field
*meanings*, and this project does not put unnamed numbers in front of users.
