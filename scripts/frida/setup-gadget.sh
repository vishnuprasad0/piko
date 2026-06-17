#!/usr/bin/env bash
# setup-gadget.sh — Full Frida Gadget setup for Instagram on a non-rooted device
#
# Encodes every step discovered during the June 2026 session:
#   • Pull Instagram APK from device via adb
#   • Inject Frida Gadget using objection (--skip-resources to avoid apktool
#     resource recompile failure on Instagram's proto-layout XML)
#   • Add libfrida-gadget.config.so UNCOMPRESSED (Instagram uses
#     extractNativeLibs=false so all .so files must be stored, not deflated)
#   • Pull Morphe.keystore from the device (BKS format, empty store password,
#     alias "Morphe", key password "Morphe")
#   • Convert BKS → PKCS12 using the BouncyCastle JAR bundled in Android SDK
#     cmdline-tools (desktop keytool / apksigner do not support BKS natively)
#   • Sign with apksigner and install as an update (no uninstall needed because
#     the Morphe key matches the already-installed piko-patched Instagram)
#   • Forward the gadget port (27043, not 27042, because Instagram spawns a
#     pretosproc process that grabs 27042 first; pick-next assigns 27043 to
#     the main com.instagram.android process)
#   • Print the frida command to load dm-hooks.js
#
# Prerequisites (install once on the Mac):
#   pip3 install objection frida-tools
#   Android SDK with build-tools ≥35 and cmdline-tools/latest installed
#   adb in PATH (or set ANDROID_HOME below)
#
# Usage:
#   bash scripts/frida/setup-gadget.sh
#
# To connect after setup (run in a separate terminal and keep open):
#   frida -H 127.0.0.1:27043 Gadget -l scripts/frida/dm-hooks.js
#
# To re-attach after killing Frida (without reinstalling):
#   adb shell am force-stop com.instagram.android
#   adb shell monkey -p com.instagram.android -c android.intent.category.LAUNCHER 1
#   # wait for "Frida   : Listening on 127.0.0.1 TCP port 270…" in logcat, then:
#   frida -H 127.0.0.1:27043 Gadget -l scripts/frida/dm-hooks.js

set -euo pipefail

# ── config ────────────────────────────────────────────────────────────────────
ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$ANDROID_SDK/build-tools/35.0.0"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
AAPT="$BUILD_TOOLS/aapt"
# BouncyCastle JAR bundled with Android cmdline-tools — no download needed
BCPROV="$ANDROID_SDK/cmdline-tools/latest/lib/external/org/bouncycastle/bcprov-jdk15on/1.67/bcprov-jdk15on-1.67.jar"

IG_PKG="com.instagram.android"
IG_ACTIVITY="com.instagram.mainactivity.InstagramMainActivity"
KEYSTORE_DEVICE_PATH="/storage/emulated/0/Download/Morphe.keystore"
KEYSTORE_ALIAS="Morphe"
KEYSTORE_PASS="Morphe"   # key password; store password is empty ""
GADGET_PORT_PRIMARY=27042
GADGET_PORT_FALLBACK=27043  # pretosproc takes 27042; main process gets 27043

WORK="/tmp/piko-frida"
mkdir -p "$WORK"

# ── helpers ───────────────────────────────────────────────────────────────────
need() { command -v "$1" &>/dev/null || { echo "[!] '$1' not found — install it first"; exit 1; }; }
info() { echo "[*] $*"; }
ok()   { echo "[+] $*"; }
die()  { echo "[!] $*"; exit 1; }

need adb
need objection
need frida
need keytool
need python3

export PATH="$BUILD_TOOLS:$PATH"
need aapt   # from build-tools, needed by objection

# ── step 1: verify device ─────────────────────────────────────────────────────
info "Checking adb device..."
adb devices | grep -v "List of" | grep "device$" | head -1 | grep -q "device" \
  || die "No device connected via adb. Run: adb devices"
ok "Device connected"

# ── step 2: pull Instagram APK ────────────────────────────────────────────────
info "Pulling Instagram APK from device..."
IG_APK_PATH=$(adb shell pm path "$IG_PKG" 2>/dev/null | sed 's/package://' | tr -d '\r')
[[ -z "$IG_APK_PATH" ]] && die "$IG_PKG not installed on device"
adb pull "$IG_APK_PATH" "$WORK/instagram.apk" 2>&1 | tail -1
ok "Pulled: $WORK/instagram.apk ($(du -sh "$WORK/instagram.apk" | cut -f1))"

# ── step 3: inject Frida Gadget ───────────────────────────────────────────────
# --skip-resources: Instagram's res/values/layouts.xml uses proto-layout
#   references that apktool cannot recompile — skipping avoids the fatal
#   AndrolibException while still allowing smali edits.
# --ignore-nativelibs: do not touch extractNativeLibs in the manifest
#   (Instagram loads .so directly from the APK zip; we keep that intact).
# --target-class: required when --skip-resources is set because objection
#   can't parse the binary AndroidManifest to auto-detect the launchable activity.
# --skip-signing: we re-sign manually with the Morphe key below.
info "Injecting Frida Gadget (this takes ~30 s)..."
rm -f "$WORK/instagram.objection.apk"
(cd "$WORK" && objection patchapk \
    -s instagram.apk \
    --skip-resources \
    --ignore-nativelibs \
    --target-class "$IG_ACTIVITY" \
    --skip-signing 2>&1) | grep -v "^$" | tail -10
[[ -f "$WORK/instagram.objection.apk" ]] || die "objection did not produce instagram.objection.apk"

# Verify the gadget .so is stored uncompressed (required for extractNativeLibs=false)
STORED=$(unzip -v "$WORK/instagram.objection.apk" "lib/arm64-v8a/libfrida-gadget.so" 2>/dev/null \
         | grep libfrida-gadget.so | awk '{print $2}')
[[ "$STORED" == "Stored" ]] \
  || echo "  [~] libfrida-gadget.so is compressed ($STORED) — may need --ignore-nativelibs"
ok "Gadget injected"

# ── step 4: add libfrida-gadget.config.so ─────────────────────────────────────
# Frida 17.x on Android requires a config file named exactly
# libfrida-gadget.config.so (not .json) — Android's APK-from-assets loader
# on API 30+ only accepts .so extensions for unextracted native libs.
# The content is plain JSON despite the .so extension.
#
# on_port_conflict=pick-next: Instagram spawns 2+ processes; the first grabs
# port 27042 (pretosproc), the main UI process gets 27043. Without pick-next
# the second process crashes with "Address already in use".
info "Adding libfrida-gadget.config.so..."
mkdir -p "$WORK/config-inject/lib/arm64-v8a"
cat > "$WORK/config-inject/lib/arm64-v8a/libfrida-gadget.config.so" << 'JSON'
{
  "interaction": {
    "type": "listen",
    "address": "127.0.0.1",
    "port": 27042,
    "on_port_conflict": "pick-next",
    "on_load": "wait"
  }
}
JSON
cp "$WORK/instagram.objection.apk" "$WORK/instagram-configured.apk"
# -0 = store without compression; must match the Stored method of libfrida-gadget.so
(cd "$WORK/config-inject" && zip -0 "$WORK/instagram-configured.apk" \
    lib/arm64-v8a/libfrida-gadget.config.so)
ok "Config added (stored, uncompressed)"

# ── step 5: pull and convert Morphe.keystore ──────────────────────────────────
# The keystore is in BKS format (BouncyCastle KeyStore), generated by Morphe
# Manager. macOS keytool and apksigner do not support BKS without an explicit
# BouncyCastle provider. We use the bcprov JAR bundled with Android cmdline-tools
# to convert BKS → PKCS12, then sign with the PKCS12.
info "Pulling Morphe.keystore from device ($KEYSTORE_DEVICE_PATH)..."
adb pull "$KEYSTORE_DEVICE_PATH" "$WORK/Morphe.keystore" 2>&1 | tail -1
[[ -f "$WORK/Morphe.keystore" ]] || die "Could not pull Morphe.keystore from device"

[[ -f "$BCPROV" ]] || die "BouncyCastle JAR not found at: $BCPROV\n  Install Android cmdline-tools via sdkmanager."

info "Converting BKS → PKCS12..."
rm -f "$WORK/Morphe.p12"
keytool -importkeystore \
  -srckeystore  "$WORK/Morphe.keystore" \
  -srcstoretype BKS \
  -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
  -providerpath  "$BCPROV" \
  -destkeystore  "$WORK/Morphe.p12" \
  -deststoretype PKCS12 \
  -srcstorepass  "" \
  -deststorepass "$KEYSTORE_PASS" \
  -srcalias      "$KEYSTORE_ALIAS" \
  -destalias     "$KEYSTORE_ALIAS" \
  -srckeypass    "$KEYSTORE_PASS" \
  -destkeypass   "$KEYSTORE_PASS" \
  -noprompt 2>&1 | grep -v "^$" || true
[[ -f "$WORK/Morphe.p12" ]] || die "BKS → PKCS12 conversion failed"
ok "Keystore converted"

# ── step 6: zipalign + sign ───────────────────────────────────────────────────
info "Zipaligning..."
"$ZIPALIGN" -f 4 "$WORK/instagram-configured.apk" "$WORK/instagram-aligned.apk"

info "Signing with Morphe key..."
"$APKSIGNER" sign \
  --ks          "$WORK/Morphe.p12" \
  --ks-key-alias "$KEYSTORE_ALIAS" \
  --ks-pass     "pass:$KEYSTORE_PASS" \
  --key-pass    "pass:$KEYSTORE_PASS" \
  --out         "$WORK/instagram-gadget.apk" \
  "$WORK/instagram-aligned.apk" 2>&1
ok "Signed: $WORK/instagram-gadget.apk ($(du -sh "$WORK/instagram-gadget.apk" | cut -f1))"

# ── step 7: install ───────────────────────────────────────────────────────────
# -r = replace existing installation, preserving app data.
# Works without uninstalling because the Morphe.keystore key matches the
# signature on the already-installed piko-patched Instagram APK.
info "Installing on device (update, no data loss)..."
adb shell am force-stop "$IG_PKG" 2>/dev/null || true
sleep 1
adb install -r "$WORK/instagram-gadget.apk" 2>&1 | tail -3

# ── step 8: launch + port forward ────────────────────────────────────────────
info "Launching Instagram..."
adb shell monkey -p "$IG_PKG" -c android.intent.category.LAUNCHER 1 2>/dev/null | grep -v "^$" || true
sleep 6

info "Forwarding ports $GADGET_PORT_PRIMARY and $GADGET_PORT_FALLBACK..."
adb forward tcp:$GADGET_PORT_PRIMARY tcp:$GADGET_PORT_PRIMARY 2>/dev/null || true
adb forward tcp:$GADGET_PORT_FALLBACK tcp:$GADGET_PORT_FALLBACK 2>/dev/null || true

# Detect which port the MAIN Instagram process bound to
# pretosproc grabs 27042 first; com.instagram.android main gets 27043
BOUND_PORT=""
for i in $(seq 1 8); do
  BOUND=$(adb logcat -d 2>/dev/null \
    | grep "Frida.*Listening on 127.0.0.1 TCP port" \
    | grep -v "^--$" \
    | awk '{print $NF}' \
    | sort -n | tail -1)
  if [[ -n "$BOUND" ]]; then
    BOUND_PORT="$BOUND"
    break
  fi
  sleep 2
done

echo ""
echo "══════════════════════════════════════════════════════"
if [[ -n "$BOUND_PORT" ]]; then
  ok "Gadget listening on port $BOUND_PORT"
  echo ""
  echo "  Connect with:"
  echo "    frida -H 127.0.0.1:$BOUND_PORT Gadget -l scripts/frida/dm-hooks.js"
else
  echo "  [~] Could not auto-detect gadget port. Try:"
  echo "    adb logcat | grep 'Frida.*Listening'"
  echo "  Then use whichever port the MAIN com.instagram.android process bound to."
  echo "    frida -H 127.0.0.1:$GADGET_PORT_FALLBACK Gadget -l scripts/frida/dm-hooks.js"
fi
echo ""
echo "  After connecting:"
echo "    1. Open a DM thread  → Hook 1 fires (REST parseFromJson)"
echo "    2. Receive a message → Hook 2 fires (MQTT A0P)"
echo "    3. Have someone unsend → look for '◄ UNSENT' in output"
echo "══════════════════════════════════════════════════════"
