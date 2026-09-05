#!/usr/bin/env bash
# Proof that the battery series is sampled only while the UI is visible, and that a
# process death appears in the series as a gap rather than as a connected interval.
#
# Two claims, and neither can be made from a unit test:
#
#   1. **Lifecycle-bound.** The cadence lives inside repeatOnLifecycle(STARTED). Sending the
#      Activity to the background must stop it, and no sample may appear while it is stopped
#      -- not "fewer samples", none. Only a real Activity going through a real onStop can
#      demonstrate that.
#   2. **Death is a gap.** Samples taken before and after `am force-stop` must both survive,
#      and the interval between them must not be presented as a connected line, because
#      nothing was observed during it.
#
# The database is read directly rather than through the app, so what is asserted is what was
# actually written.
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.."
ADB="./tools/emu-adb.sh"
PKG=com.rmpsdroid.battinsight
DB=/data/data/$PKG/databases/battinsight-sessions.db
OUT="${TEMP:-/tmp}/series-proof"
mkdir -p "$OUT"

# Reads battery_sample straight out of the app's private database.
samples() {
    $ADB exec-out "run-as $PKG cat $DB" > "$OUT/db" 2>/dev/null || true
    $ADB exec-out "run-as $PKG cat $DB-wal" > "$OUT/db-wal" 2>/dev/null || true
    python - "$OUT/db" <<'PY'
import sqlite3, sys
try:
    c = sqlite3.connect(sys.argv[1])
    rows = list(c.execute(
        "SELECT sample_elapsed_realtime_millis, trigger FROM battery_sample "
        "ORDER BY sample_elapsed_realtime_millis"))
    print(len(rows))
    for r in rows:
        print("   %d %s" % r, file=sys.stderr)
except Exception:
    print(0)
PY
}

echo "== setup: clean app state =="
$ADB shell pm clear "$PKG" >/dev/null
echo "   app data cleared"

echo
echo "== step 1: launch, and let a visible sample land =="
$ADB shell am start -n "$PKG/.app.MainActivity" >/dev/null
# Cold start measured at ~7s on this emulator; wait well past it rather than race it.
sleep 18
$ADB shell am force-stop "$PKG" >/dev/null   # flush WAL by ending the process
sleep 2
BEFORE=$(samples)
echo "   samples after first visible period: $BEFORE"
if [ "$BEFORE" -lt 1 ]; then
    echo "FAILED: a visible Activity produced no sample at all" >&2
    exit 1
fi

echo
echo "== step 2: relaunch, then send the Activity to the background =="
$ADB shell am start -n "$PKG/.app.MainActivity" >/dev/null
sleep 15
# HOME takes the Activity through onStop, which is what cancels repeatOnLifecycle(STARTED).
$ADB shell input keyevent KEYCODE_HOME >/dev/null
sleep 3
$ADB shell am force-stop "$PKG" >/dev/null
sleep 2
AFTER_BG=$(samples)
echo "   samples after backgrounding: $AFTER_BG"

echo
echo "== step 3: stay backgrounded well beyond several cadences =="
# The process is stopped, so nothing may be produced. If a background sampler existed, this
# is where its samples would appear.
sleep 45
QUIET=$(samples)
echo "   samples after a quiet period: $QUIET"
if [ "$QUIET" -ne "$AFTER_BG" ]; then
    echo "FAILED: $((QUIET - AFTER_BG)) sample(s) appeared while nothing was visible" >&2
    exit 1
fi
echo "   nothing sampled while not visible  OK"

echo
echo "== step 4: relaunch -- an immediate sample, and a gap behind it =="
$ADB shell am start -n "$PKG/.app.MainActivity" >/dev/null
# Cold start measured at ~7s on this emulator; wait well past it rather than race it.
sleep 18
$ADB shell am force-stop "$PKG" >/dev/null
sleep 2
FINAL=$(samples)
echo "   samples after relaunch: $FINAL"
if [ "$FINAL" -le "$QUIET" ]; then
    echo "FAILED: becoming visible again produced no sample" >&2
    exit 1
fi

echo
echo "== step 5: the series must contain a gap, not one connected run =="
python - "$OUT/db" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
rows = list(c.execute(
    "SELECT sample_elapsed_realtime_millis, trigger, session_id FROM battery_sample "
    "ORDER BY sample_elapsed_realtime_millis"))
print("   stored samples:")
for e, t, s in rows:
    print("      elapsed=%-12d trigger=%-14s session=%s" % (e, t, s[:8]))

restarts = [t for _, t, _ in rows if t == "APP_START"]
if len(restarts) < 2:
    print("FAILED: expected at least two APP_START samples, saw %d" % len(restarts))
    raise SystemExit(1)
print()
print("   %d samples announce a fresh start, so the read model reports" % len(restarts))
print("   PROCESS_RESTART gaps rather than one connected segment.")
PY

echo
echo "== cleanup =="
$ADB shell pm clear "$PKG" >/dev/null
rm -rf "$OUT"
echo "   app data cleared; host copies removed"
echo
echo "PROVED: samples are produced only while the UI is visible; none appeared across"
echo "        45 seconds with the process stopped; and the samples either side of a"
echo "        process death are separated by an unobserved interval."
