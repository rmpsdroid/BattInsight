#!/usr/bin/env bash
#
# Proves a session's counter baseline survives the death of the process that established it.
#
# Three instrumentation runs with `am force-stop` between them. Instrumentation executes
# inside the application's own process, so the kill is real: each step reads a database
# written by a process that no longer exists. Each step reports its pid and the harness
# requires them to differ, because two steps sharing a warm process would prove nothing.
#
# Read-only against the device: one `dumpsys batterystats -c`, no reset, no battery
# simulation, no permission or app-op change, no root. Emulator only -- see tools/emu-adb.sh.
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

cd "$(dirname "$0")/.."
ADB=./tools/emu-adb.sh
PKG=com.rmpsdroid.battinsight
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$PKG.CounterProcessDeathTest"
WORK="build/p7b-proof"
mkdir -p "$WORK"

field() { $ADB logcat -d -s BattInsightCounters:I | sed -n "s/.*COUNTER_PROOF $1=\(.*\)/\1/p" | tail -1 | tr -d '\r'; }

run_step() {
  local name="$1"; shift
  local out
  out="$($ADB shell am instrument -w -r -e class "$CLASS#$name" "$@" "$RUNNER" 2>&1)"
  if ! echo "$out" | grep -qE "^OK \("; then
    echo "FAILED: $name" >&2; echo "$out" | tail -40 >&2; exit 1
  fi
  echo "   $name: OK"
}

echo "== capture a read-only payload for the decoder =="
$ADB shell dumpsys batterystats -c > "$WORK/raw" 2>/dev/null
python -c "
d=open(r'$WORK/raw','rb').read().replace(b'\r\n',b'\n')
open(r'$WORK/capture','wb').write(d); print('   bytes: %d' % len(d))
"
DEST="/sdcard/Android/data/$PKG/files/counter-capture.checkin"
$ADB shell mkdir -p "/sdcard/Android/data/$PKG/files"
$ADB push "$WORK/capture" "$DEST" >/dev/null
$ADB logcat -c

echo "== step 1: establish the baseline =="
run_step captureA
SESSION=$(field sessionId); BASELINE=$(field baselineCaptureId)
KWL=$(field kwl); PWL=$(field pwl); PID1=$(field pid)
echo "   session=$SESSION"
echo "   baseline=$BASELINE  kwl=$KWL  pwl=$PWL  pid=$PID1"

echo "== step 2: kill the process =="
$ADB shell am force-stop "$PKG"

echo "== step 3: second capture in a new process =="
$ADB logcat -c
run_step captureB -e expectedSessionId "$SESSION" -e expectedBaselineCaptureId "$BASELINE"
BASELINE_B=$(field baselineAfterB); LATEST_B=$(field latestAfterB)
CAPTURES=$(field captures); PID2=$(field pid)
echo "   baseline=$BASELINE_B  latest=$LATEST_B  captures=$CAPTURES  pid=$PID2"

echo "== step 4: kill again =="
$ADB shell am force-stop "$PKG"

echo "== step 5: read cold and compute the delta =="
$ADB logcat -c
run_step verifyAfterSecondDeath \
  -e expectedSessionId "$SESSION" \
  -e expectedBaselineCaptureId "$BASELINE" \
  -e expectedLatestCaptureId "$LATEST_B"
KWLD=$(field kwlDeltas); ADV=$(field kwlAdvanced); SAMPLE=$(field sampleDeltaMillis)
PWLD=$(field pwlDeltas); PID3=$(field pid)
echo "   comparable=yes kwlDeltas=$KWLD advanced=$ADV sampleMs=$SAMPLE pwlDeltas=$PWLD pid=$PID3"

echo "== checks =="
fail=0
chk() { if [ "$2" = "$3" ]; then printf "   %-26s %s  OK\n" "$1" "$2"; else printf "   %-26s got '%s' expected '%s'  FAIL\n" "$1" "$2" "$3"; fail=1; fi; }
chk "baseline unchanged" "$BASELINE_B" "$BASELINE"
chk "captures bounded" "$CAPTURES" "2"
[ "$LATEST_B" != "$BASELINE" ] && echo "   latest advanced          $LATEST_B  OK" || { echo "   latest advanced          FAIL"; fail=1; }
if [ "$PID1" != "$PID2" ] && [ "$PID2" != "$PID3" ]; then
  echo "   three distinct processes $PID1 -> $PID2 -> $PID3  OK"
else
  echo "   three distinct processes $PID1 -> $PID2 -> $PID3  FAIL"; fail=1
fi

echo "== cleanup =="
$ADB shell rm -f "$DEST"; rm -f "$WORK/raw" "$WORK/capture"
echo "   device capture removed; host copies removed"

[ "$fail" -eq 0 ] || { echo "PROOF FAILED" >&2; exit 1; }
echo
echo "PROVED: baseline $BASELINE established in pid $PID1 survived two process kills;"
echo "        latest advanced to $LATEST_B; storage bounded at $CAPTURES captures."
