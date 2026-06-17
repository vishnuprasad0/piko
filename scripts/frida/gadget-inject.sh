#!/usr/bin/env bash
# gadget-inject.sh — Embed Frida Gadget into a patched Instagram APK
#
# Use this when you DON'T have a rooted device.
# Produces <apk>-frida.apk that you install instead of the original.
# After launching Instagram, connect with: frida -U gadget -l dm-hooks.js
#
# Prerequisites (install once):
#   pip install objection
#   brew install apktool  (or download apktool.jar manually)
#
# Usage:
#   bash scripts/frida/gadget-inject.sh path/to/Instagram-piko-patched.apk

set -euo pipefail

INPUT="${1:-}"
if [[ -z "$INPUT" || ! -f "$INPUT" ]]; then
    echo "Usage: $0 <patched-instagram.apk>"
    echo ""
    echo "Run  objection patchapk -s <apk>  to embed Frida Gadget."
    echo "This script is a wrapper that also installs via adb if a device is connected."
    exit 1
fi

BASENAME="${INPUT%.apk}"
OUTPUT="${BASENAME}-frida.apk"

echo "[*] Embedding Frida Gadget via objection..."
# objection patchapk produces <name>.objection.apk
objection patchapk -s "$INPUT"
OBJECTION_OUT="${BASENAME}.objection.apk"

if [[ ! -f "$OBJECTION_OUT" ]]; then
    echo "[!] objection did not produce expected output: $OBJECTION_OUT"
    exit 1
fi

mv "$OBJECTION_OUT" "$OUTPUT"
echo "[+] Gadget APK: $OUTPUT"

# Install if device is connected
if adb devices 2>/dev/null | grep -q "device$"; then
    echo "[*] Device detected — installing..."
    adb install -r "$OUTPUT"
    echo "[+] Installed. Launch Instagram, then run:"
    echo "      frida -U gadget -l scripts/frida/dm-hooks.js"
else
    echo "[~] No device connected via adb. Install $OUTPUT manually, then:"
    echo "      frida -U gadget -l scripts/frida/dm-hooks.js"
fi
