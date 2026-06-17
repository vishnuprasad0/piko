# setup-gadget-rooted.ps1 - Frida setup for ROOTED devices or Android emulator (Windows)
#
# Uses frida-server (no APK patching needed) — much simpler than gadget injection.
# Perfect for Android emulators or rooted devices.
#
# Prerequisites:
#   - Android emulator (rooted by default) or rooted device via adb
#   - Frida CLI: pip install "frida==17.9.3" frida-tools
#   - Download frida-server for Android:
#     This script will download it automatically
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget-rooted.ps1
#
# After setup, connect with:
#   frida -U -f com.instagram.android -l scripts/frida/dm-hooks.js --no-pause
#   (or if Instagram is already running)
#   frida -U com.instagram.android -l scripts/frida/dm-hooks.js

$ErrorActionPreference = "Stop"

$FRIDA_VERSION = "17.9.3"
$IG_PKG = "com.instagram.android"
$WORK = "$env:TEMP\piko-frida"

New-Item -ItemType Directory -Path $WORK -Force | Out-Null

function Info($msg)  { Write-Host "[*] $msg" -ForegroundColor Cyan }
function Ok($msg)    { Write-Host "[+] $msg" -ForegroundColor Green }
function Die($msg)   { Write-Host "[!] $msg" -ForegroundColor Red; exit 1 }

# -- STEP 1: Verify device --

Info "Checking for connected device (rooted or emulator)..."
$devices = adb devices
if (-not ($devices | Select-String "device`$")) {
    Die "No device connected. Run: adb devices`nMake sure the device is rooted or an emulator."
}
Ok "Device connected"

# -- STEP 2: Verify root access --

Info "Checking for root access..."
$root_check = adb shell "id" 2>$null
if ($root_check -notmatch "uid=0") {
    Die "Device is not rooted. Run this on a rooted device or Android emulator."
}
Ok "Root access confirmed"

# -- STEP 3: Download frida-server --

Info "Downloading frida-server $FRIDA_VERSION..."
$arch = adb shell "getprop ro.product.cpu.abi" 2>$null | ForEach-Object { $_ -replace '\r$', '' }
if (-not $arch) { $arch = "arm64-v8a" }
Write-Host "  Architecture: $arch"

$server_url = "https://github.com/frida/frida/releases/download/$FRIDA_VERSION/frida-server-$FRIDA_VERSION-android-$arch.xz"
$server_xz = "$WORK\frida-server.xz"
$server_bin = "$WORK\frida-server"

if (-not (Test-Path $server_bin)) {
    Invoke-WebRequest -Uri $server_url -OutFile $server_xz -ErrorAction Stop -UseBasicParsing
    Write-Host "  Downloaded: $server_xz"

    # Decompress .xz (use 7-Zip if available, else show manual instructions)
    $7z = "C:\Program Files\7-Zip\7z.exe"
    if (Test-Path $7z) {
        & $7z x $server_xz -o"$WORK" -y >$null 2>&1
        Write-Host "  Extracted with 7-Zip"
    } else {
        Die "XZ extraction needed. Install 7-Zip or decompress manually:`n  7z x $server_xz -o$WORK"
    }
}
Ok "frida-server ready: $server_bin"

# -- STEP 4: Push to device and start --

Info "Pushing frida-server to device..."
adb push $server_bin /data/local/tmp/frida-server
adb shell chmod +x /data/local/tmp/frida-server
Ok "frida-server installed on device"

Info "Starting frida-server in background..."
adb shell "/data/local/tmp/frida-server &"
Start-Sleep -Seconds 3

# Verify it's listening
$ps_check = adb shell "ps | grep frida-server" 2>$null
if ($ps_check) {
    Ok "frida-server is running"
} else {
    Write-Host "  [~] Warning: frida-server may not be running yet" -ForegroundColor Yellow
}

# -- FINAL INSTRUCTIONS --

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  frida-server setup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "  Connect with one of these commands:" -ForegroundColor White
Write-Host ""
Write-Host "    # Launch Instagram and attach Frida in one go:"
Write-Host "    frida -U -f $IG_PKG -l scripts/frida/dm-hooks.js --no-pause"
Write-Host ""
Write-Host "    # Or if Instagram is already running:"
Write-Host "    frida -U $IG_PKG -l scripts/frida/dm-hooks.js"
Write-Host ""
Write-Host "  After connecting:" -ForegroundColor White
Write-Host "    1. Open a DM thread  -> Hook 1 fires (REST parseFromJson)"
Write-Host "    2. Receive a message -> Hook 2 fires (MQTT A0P)"
Write-Host "    3. Have someone unsend -> look for 'UNSENT' in output"
Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
