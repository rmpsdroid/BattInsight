#!/usr/bin/env bash
#
# ADB, restricted to an emulator.
#
# Every instrumented step in this project goes through here rather than calling adb
# directly, for one reason: a physical Samsung SM_M305F is routinely attached over
# wireless ADB, and the bare `adb` command with no serial picks a device for you when
# exactly one is attached -- or fails ambiguously when two are. Neither is acceptable
# when half the commands in this repository read battery state.
#
# The guard is the serial pattern. Emulators are always `emulator-<port>`; a wireless
# device is `<ip>:<port>` and a USB device is a hardware serial, so neither can match.
# The check is on the *serial*, not on a device property, because a property has to be
# read from the device -- which would mean touching it to find out whether we may.
#
# Usage:  tools/emu-adb.sh shell getprop ro.build.version.sdk
#         BATTINSIGHT_SERIAL=emulator-5556 tools/emu-adb.sh install -r app.apk
set -euo pipefail

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="C:/Users/$USERNAME/AppData/Local/Android/Sdk/platform-tools/adb.exe"

serial="${BATTINSIGHT_SERIAL:-}"
if [ -z "$serial" ]; then
    mapfile -t emulators < <("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}')
    if [ "${#emulators[@]}" -eq 0 ]; then
        echo "refusing: no emulator is attached." >&2
        echo "attached devices:" >&2
        "$ADB" devices -l | sed 's/^/    /' >&2
        exit 2
    fi
    if [ "${#emulators[@]}" -gt 1 ]; then
        echo "refusing: ${#emulators[@]} emulators attached; set BATTINSIGHT_SERIAL." >&2
        printf '    %s\n' "${emulators[@]}" >&2
        exit 2
    fi
    serial="${emulators[0]}"
fi

case "$serial" in
    emulator-[0-9]*) ;;
    *)
        echo "refusing: '$serial' is not an emulator serial." >&2
        echo "This project runs instrumented steps on the Pixel_8 emulator only." >&2
        exit 2
        ;;
esac

# `install -g` grants every requested permission without a prompt, which silently
# invalidates any test of the permission flow -- and did once, in an earlier phase.
for arg in "$@"; do
    if [ "$arg" = "-g" ]; then
        echo "refusing: 'adb install -g' pre-grants permissions and hides the real flow." >&2
        exit 2
    fi
done

exec "$ADB" -s "$serial" "$@"
