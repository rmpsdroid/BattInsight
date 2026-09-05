#!/usr/bin/env bash
#
# Cross-checks the production decoder against an independent count of the same payload.
#
# Takes one read-only `dumpsys batterystats -c` capture over ADB, counts its records with awk
# on the host, pushes the same bytes to the device, and decodes them there with production
# code. Two independent implementations reading identical bytes must agree.
#
# The capture is never committed: it is a live dump of the device's package list and
# wakelocks. It is deleted from the device at the end.
#
# Emulator only -- see tools/emu-adb.sh. Read-only: no reset, no battery simulation, no
# permission or app-op change.
set -euo pipefail

# Git Bash rewrites arguments that look like POSIX paths into Windows ones, which turns
# /sdcard/... into C:\Program Files\... before adb ever sees it. Device paths are not host
# paths, so the conversion is switched off for this script.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

cd "$(dirname "$0")/.."
ADB=./tools/emu-adb.sh
PKG=com.rmpsdroid.battinsight
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
# Deliberately under build/, not $TMPDIR: this script mixes Git Bash and Windows Python, and
# a POSIX /tmp path is invisible to the latter. A repo-relative path resolves identically in
# both. build/ is git-ignored, and the files are deleted at the end regardless.
WORK="build/p7a-decode-check"
mkdir -p "$WORK"

echo "== capture (read-only) =="
$ADB shell dumpsys batterystats -c > "$WORK/raw" 2>/dev/null
# adb shell translates LF to CRLF on Windows; normalise so both sides see identical bytes.
python -c "
import sys
d=open(r'$WORK/raw','rb').read().replace(b'\r\n',b'\n')
open(r'$WORK/capture','wb').write(d)
print('   bytes: %d' % len(d))
"

echo "== independent count (awk, not our decoder) =="
F="$WORK/capture"
H_BYTES=$(wc -c < "$F" | tr -d ' ')
H_KWL=$(awk -F',' '$2!="h" && $4=="kwl"' "$F" | wc -l | tr -d ' ')
H_WL=$(awk -F',' '$2!="h" && $4=="wl"' "$F" | wc -l | tr -d ' ')
H_UID=$(awk -F',' '$2!="h" && $4=="uid"' "$F" | wc -l | tr -d ' ')
H_HIST=$(grep -c '^9,h,' "$F" | tr -d ' ')
H_VERS=$(grep -m1 ',vers,' "$F" | cut -d',' -f5)
echo "   bytes=$H_BYTES vers=$H_VERS kwl=$H_KWL wl=$H_WL uid=$H_UID history=$H_HIST"

echo "== push to the app's own external files dir (no permission needed) =="
DEST="/sdcard/Android/data/$PKG/files/runtime-capture.checkin"
$ADB shell mkdir -p "/sdcard/Android/data/$PKG/files"
$ADB push "$F" "$DEST" >/dev/null
$ADB logcat -c

echo "== decode on device with production code =="
$ADB shell am instrument -w -r \
  -e class com.rmpsdroid.battinsight.BatteryStatsDecodeRuntimeTest "$RUNNER" 2>&1 \
  | grep -E "^OK \(|^Tests run|FAILURES" || true

vals="$($ADB logcat -d -s BattInsightDecode:I | sed -n 's/.*DECODE_CHECK \([a-zA-Z]*\)=\([0-9]*\).*/\1=\2/p')"
echo "   device decoder reported:"
echo "$vals" | sed 's/^/     /'

get() { echo "$vals" | grep "^$1=" | tail -1 | cut -d= -f2; }
D_BYTES=$(get bytes); D_KWL=$(get kwl); D_WL=$(get wl); D_UID=$(get uidMappings)
D_HIST=$(get history); D_VERS=$(get checkinVersion)

echo "== comparison =="
fail=0
cmp_one() {
  if [ "$2" = "$3" ]; then printf "   %-10s host=%-8s device=%-8s MATCH\n" "$1" "$2" "$3"
  else printf "   %-10s host=%-8s device=%-8s MISMATCH\n" "$1" "$2" "$3"; fail=1; fi
}
cmp_one bytes   "$H_BYTES" "$D_BYTES"
cmp_one vers    "$H_VERS"  "$D_VERS"
cmp_one kwl     "$H_KWL"   "$D_KWL"
cmp_one wl      "$H_WL"    "$D_WL"
cmp_one uid     "$H_UID"   "$D_UID"
cmp_one history "$H_HIST"  "$D_HIST"

echo "== cleanup =="
$ADB shell rm -f "$DEST"
rm -f "$WORK/raw" "$WORK/capture"
echo "   device capture removed; host copies removed"

[ "$fail" -eq 0 ] || { echo "CROSS-CHECK FAILED" >&2; exit 1; }
echo
echo "CROSS-CHECK PASSED: the production decoder agrees with an independent count."
