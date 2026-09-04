#!/usr/bin/env bash
#
# Runs the two halves of ProcessDeathRecoveryTest with a real process kill between them.
#
# The kill is the experiment. Two @Test methods in one instrumentation run would share a
# process, a Room singleton and a warm page cache, and would pass whether or not anything
# was ever written to disk. Splitting them across invocations, with `am force-stop` in
# between, is what makes the second half's success mean the data came off the filesystem.
#
# Emulator only -- see tools/emu-adb.sh.
set -euo pipefail

cd "$(dirname "$0")/.."
ADB=./tools/emu-adb.sh
PKG=com.rmpsdroid.battinsight
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$PKG.ProcessDeathRecoveryTest"

# `am instrument` reports success as INSTRUMENTATION_CODE: -1, which is Activity.RESULT_OK
# and not an error however much it looks like one. The reliable signal is JUnit's own
# summary line, so that is what gets checked.
run_one() {
    $ADB shell am instrument -w -r "$@" "$RUNNER" 2>&1
}

assert_passed() {
    local output="$1" step="$2"
    if ! echo "$output" | grep -qE "^OK \("; then
        echo "FAILED: $step" >&2
        echo "$output" | tail -40 >&2
        exit 1
    fi
}

echo "== step 1: create and store a session =="
# Cleared so the tag below carries exactly this run's line and not a previous one's.
$ADB logcat -c
first="$(run_one -e class "$CLASS#recordSession")"
assert_passed "$first" "step 1 could not create and store a session"
echo "$first" | grep -E "^OK \(|^Tests run"

# The id comes from logcat, not stdout: an instrumented test's println reaches
# neither the runner's stream nor this script.
session_id="$($ADB logcat -d -s BattInsightProof:I | sed -n 's/.*BATTINSIGHT_SESSION_ID=\([0-9a-f-]\{36\}\).*/\1/p' | head -1)"
if [ -z "$session_id" ]; then
    echo "FAILED: step 1 did not report a session id" >&2
    echo "$first" >&2
    exit 1
fi
pid_one="$($ADB logcat -d -s BattInsightProof:I | sed -n 's/.*BATTINSIGHT_PID=\([0-9]\{1,\}\).*/\1/p' | head -1)"
echo "   stored session: $session_id (written by pid ${pid_one:-unknown})"

echo "== step 2: kill the process that created it =="
# An instrumentation process exits when its run ends, so by now the process from step 1 is
# usually already gone -- which is fine, and is itself process death. force-stop is what
# makes that guaranteed rather than incidental, and the pids are printed as evidence of
# which of the two happened.
pid_before="$($ADB shell pidof "$PKG" | tr -d '\r' || true)"
echo "   pid before: ${pid_before:-none}"
$ADB shell am force-stop "$PKG"
pid_after="$($ADB shell pidof "$PKG" | tr -d '\r' || true)"
echo "   pid after:  ${pid_after:-none}"
if [ -n "$pid_after" ] && [ "$pid_after" = "$pid_before" ]; then
    echo "FAILED: the process survived force-stop; the proof would be meaningless" >&2
    exit 1
fi

echo "== step 3: a new process must recover the same session =="
second="$(run_one -e class "$CLASS#resumeSession" -e expectedSessionId "$session_id")"
assert_passed "$second" "the session did not survive process death"
echo "$second" | grep -E "^OK \(|^Tests run"

pid_two="$($ADB logcat -d -s BattInsightProof:I | sed -n 's/.*BATTINSIGHT_PID=\([0-9]\{1,\}\).*/\1/p' | tail -1)"
echo "   recovered by pid ${pid_two:-unknown}"
if [ -z "$pid_one" ] || [ -z "$pid_two" ]; then
    echo "FAILED: could not determine both process ids; the proof is inconclusive" >&2
    exit 1
fi
if [ "$pid_one" = "$pid_two" ]; then
    echo "FAILED: both halves ran in pid $pid_one, so nothing was recovered from disk" >&2
    exit 1
fi

echo
echo "PROVED: session $session_id was written by pid $pid_one and recovered by pid $pid_two."
