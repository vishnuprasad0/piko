#!/usr/bin/env bash
# connect.sh — Launch Instagram with Frida gadget and attach immediately.
#
# on_load=wait means Instagram freezes at splash until Frida connects.
# This script does both in sequence so you never hit the ANR window.
#
# Usage:
#   bash scripts/frida/connect.sh                      # loads dm-hooks.js
#   bash scripts/frida/connect.sh discover-classes.js  # loads a different script
#
# After Frida connects, Instagram resumes normally.
# To run again: just re-run this script (force-stops and relaunches cleanly).

set -euo pipefail

SCRIPT="${1:-$(dirname "$0")/dm-hooks.js}"
[[ -f "$SCRIPT" ]] || { echo "[!] Script not found: $SCRIPT"; exit 1; }

IG_PKG="com.instagram.android"
PORT=27043   # pretosproc grabs 27042; main process gets 27043 via pick-next

echo "[*] Force-stopping Instagram..."
adb shell am force-stop "$IG_PKG" 2>/dev/null || true
sleep 1

adb forward tcp:27042 tcp:27042 2>/dev/null || true
adb forward tcp:27043 tcp:27043 2>/dev/null || true

echo "[*] Launching Instagram (will pause at splash waiting for Frida)..."
adb shell monkey -p "$IG_PKG" -c android.intent.category.LAUNCHER 1 2>/dev/null | grep -v "^$" || true

# Wait for gadget to start listening — usually < 3 s, well within ANR window
echo "[*] Waiting for gadget to listen..."
for i in $(seq 1 15); do
    if adb logcat -d 2>/dev/null | grep "Frida.*Listening on 127.0.0.1 TCP port $PORT" | grep -q "$PORT"; then
        break
    fi
    sleep 1
done

echo "[+] Gadget ready on port $PORT. Connecting Frida..."
echo "[~] Instagram will resume as soon as Frida attaches."
echo ""
frida -H "127.0.0.1:$PORT" Gadget -l "$SCRIPT"
