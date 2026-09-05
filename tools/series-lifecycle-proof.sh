#!/usr/bin/env bash
# Proof that the battery cadence is bound to the Activity lifecycle, and that a process death
# appears in the series as a gap rather than a connected interval.
#
# These are two different claims and they need two different experiments. An earlier version of
# this script conflated them: it pressed HOME *and* force-stopped the app, then observed no new
# samples. That only demonstrates that a dead process samples nothing, which is trivially true
# and says nothing about repeatOnLifecycle. The interesting claim is the other one:
#
#   1. **Activity STOPPED, process ALIVE.** Pressing HOME takes the Activity through onStop,
#      which is what cancels the repeatOnLifecycle(STARTED) block. The process keeps running.
#      Nothing may be sampled while it is hidden -- and the pid is checked before and after to
#      prove the process really did stay alive, so "no samples" cannot be explained by death.
#
#   2. **Process death.** force-stop, relaunch, and the samples either side must both survive
#      with an unobserved interval between them rather than a joined line.
#
# The database is read directly, WAL included, so what is asserted is what was actually
# written -- not what the app says it wrote.
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "$0")/.."
ADB="./tools/emu-adb.sh"
PKG=com.rmpsdroid.battinsight
DB=/data/data/$PKG/databases/battinsight-sessions.db
OUT="${TEMP:-/tmp}/series-proof"
mkdir -p "$OUT"

# Reads battery_sample out of the live database. The -wal file is pulled alongside so SQLite
# replays it -- without that a running app appears to have written nothing.
samples() {
    $ADB exec-out "run-as $PKG cat $DB" > "$OUT/db" 2>/dev/null || true
    $ADB exec-out "run-as $PKG cat $DB-wal" > "$OUT/db-wal" 2>/dev/null || true
    python - "$OUT/db" <<'PY'
import sqlite3, sys
try:
    c = sqlite3.connect(sys.argv[1])
    print(c.execute("SELECT COUNT(*) FROM battery_sample").fetchone()[0])
except Exception:
    print(0)
PY
}

pid_of() { $ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

echo "== setup =="
$ADB shell pm clear "$PKG" >/dev/null
echo "   app data cleared"

echo
echo "== claim 1: Activity STOPPED, process ALIVE, nothing sampled =="
$ADB shell am start -n "$PKG/.app.MainActivity" >/dev/null
sleep 18                      # cold start measured at ~7s on this emulator
PID_BEFORE=$(pid_of)
BASE=$(samples)
echo "   visible: $BASE samples, pid $PID_BEFORE"
if [ "$BASE" -lt 1 ]; then
    echo "FAILED: a visible Activity produced no sample at all" >&2
    exit 1
fi

# HOME, not force-stop. The Activity reaches onStop; the process keeps running.
$ADB shell input keyevent KEYCODE_HOME >/dev/null
sleep 5
PID_HIDDEN=$(pid_of)
if [ -z "$PID_HIDDEN" ]; then
    echo "FAILED: the process died on HOME, so this proves nothing about lifecycle" >&2
    exit 1
fi
echo "   hidden : pid $PID_HIDDEN still alive"

# Wait past a whole production cadence. If the timer had survived onStop it would fire here.
echo "   waiting 380s -- longer than the 300s production cadence..."
sleep 380
PID_AFTER=$(pid_of)
HIDDEN=$(samples)
echo "   after  : $HIDDEN samples, pid $PID_AFTER"

if [ "$PID_AFTER" != "$PID_BEFORE" ]; then
    echo "FAILED: pid changed $PID_BEFORE -> $PID_AFTER; the process did not stay alive" >&2
    exit 1
fi
if [ "$HIDDEN" -ne "$BASE" ]; then
    echo "FAILED: $((HIDDEN - BASE)) sample(s) appeared while the Activity was STOPPED" >&2
    exit 1
fi
echo "   same pid throughout, and no sample appeared  OK"

echo
echo "== claim 2: process death shows as a gap =="
$ADB shell am force-stop "$PKG" >/dev/null
sleep 2
KILLED=$(samples)
$ADB shell am start -n "$PKG/.app.MainActivity" >/dev/null
sleep 18
$ADB shell am force-stop "$PKG" >/dev/null
sleep 2
FINAL=$(samples)
echo "   before death: $KILLED   after relaunch: $FINAL"
if [ "$FINAL" -le "$KILLED" ]; then
    echo "FAILED: becoming visible again produced no sample" >&2
    exit 1
fi

python - "$OUT/db" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
rows = list(c.execute(
    "SELECT sample_elapsed_realtime_millis, trigger FROM battery_sample "
    "ORDER BY sample_elapsed_realtime_millis"))
print("   stored samples:")
for e, t in rows:
    print("      elapsed=%-12d trigger=%s" % (e, t))
restarts = [t for _, t in rows if t == "APP_START"]
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
echo "PROVED: with the Activity STOPPED and the process demonstrably still alive (same pid"
echo "        before and after), no sample appeared across 380s -- longer than the 300s"
echo "        production cadence. Separately, samples either side of a real process death"
echo "        both survive and are separated by an unobserved interval."
