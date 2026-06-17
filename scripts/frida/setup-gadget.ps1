# setup-gadget.ps1 - Frida Gadget setup for Instagram on non-rooted device (Windows)
#
# Same workflow as setup-gadget.sh (Mac), adapted for Windows PowerShell.
# Injects the gadget WITHOUT apktool (avoids the Windows apktool rebuild hang):
#   - Uses baksmali to disassemble only classes2.dex (contains InstagramMainActivity)
#   - Patches the smali to add System.loadLibrary("frida-gadget")
#   - Uses smali to reassemble just that dex
#   - Replaces the dex + adds libfrida-gadget.so + config directly into the APK zip
#   - Signs with Morphe key using apksigner
#
# Prerequisites (install once; match the frida CLI to the gadget version):
#   pip install "frida==17.14.1" frida-tools
#   Android SDK with build-tools >=35 installed
#   Java 17 (keytool + java in PATH)
#   adb in PATH
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget.ps1 -GadgetVersion 17.9.3
#
# -GadgetVersion: which Frida gadget to embed (default 17.14.1, verified on mt6855 /
#   Android 15). On MediaTek the Java bridge SIGILLs on some versions — if Java.use()
#   throws "illegal instruction", sweep versions. Match the frida CLI to the gadget.
#
# Re-attach after killing Frida (no reinstall needed):
#   adb shell am force-stop com.instagram.android
#   adb shell monkey -p com.instagram.android -c android.intent.category.LAUNCHER 1
#   frida -H 127.0.0.1:27043 Gadget -l scripts/frida/dm-hooks.js

param(
    # 17.14.1 verified working on MediaTek mt6855 / Android 15 (Java bridge builds
    # class wrappers without SIGILL). 17.9.3 SIGILLs on that chip. Older chips (e.g.
    # Moto g64 5G) needed 17.9.3 and SIGILL on 17.14.x — so this is device-specific;
    # sweep versions if Java.use() throws "illegal instruction" on a new device.
    [string]$GadgetVersion = "17.14.1"
)

$ErrorActionPreference = "Stop"

# -- CONFIG --

$ANDROID_SDK   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:USERPROFILE\AppData\Local\Android\sdk" }
$BUILD_TOOLS   = "$ANDROID_SDK\build-tools\35.0.0"
$APKSIGNER     = "$BUILD_TOOLS\apksigner.bat"
$ZIPALIGN      = "$BUILD_TOOLS\zipalign.exe"

$BCPROV = "$ANDROID_SDK\cmdline-tools\latest\lib\external\org\bouncycastle\bcprov-jdk15on\1.67\bcprov-jdk15on-1.67.jar"

$IG_PKG        = "com.instagram.android"
$IG_ACTIVITY   = "com/instagram/mainactivity/InstagramMainActivity"  # smali path form
$KEYSTORE_DEVICE_PATH = "/storage/emulated/0/Download/Morphe.keystore"
$KEYSTORE_ALIAS = "Morphe"
$KEYSTORE_PASS  = "Morphe"   # key password; store password is empty

$GADGET_VERSION       = $GadgetVersion
$GADGET_PORT_PRIMARY  = 27042
$GADGET_PORT_FALLBACK = 27043

$WORK = "$env:TEMP\piko-frida"
New-Item -ItemType Directory -Path $WORK -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# -- HELPERS --

function Need($cmd) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Host "[!] '$cmd' not found - install it first" -ForegroundColor Red; exit 1
    }
}
function Info($msg) { Write-Host "[*] $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "[+] $msg" -ForegroundColor Green }
function Die($msg)  { Write-Host "[!] $msg" -ForegroundColor Red; exit 1 }

Need adb
Need java
Need keytool
Need frida

# -- STEP 1: Verify device --

Info "Checking adb device..."
if (-not (adb devices | Select-String "device`$")) { Die "No device connected. Run: adb devices" }
Ok "Device connected"

# -- STEP 2: Pull Instagram APK --

Info "Pulling Instagram APK from device..."
$IG_APK_PATH = (adb shell pm path $IG_PKG 2>$null) -replace 'package:', '' | Select-Object -First 1
if (-not $IG_APK_PATH) { Die "$IG_PKG not installed on device" }

adb pull $IG_APK_PATH.Trim() "$WORK\instagram.apk" 2>&1 | Select-Object -Last 1
$sizeMB = [math]::Round((Get-Item "$WORK\instagram.apk").Length / 1MB, 1)
Ok "Pulled: $WORK\instagram.apk ($sizeMB MB)"

# -- STEP 3a: Download tools (baksmali, smali, frida-gadget) --

$BAKSMALI_URL = "https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar"
$SMALI_URL    = "https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar"
$GADGET_URL   = "https://github.com/frida/frida/releases/download/$GADGET_VERSION/frida-gadget-$GADGET_VERSION-android-arm64.so.xz"

if (-not (Test-Path "$WORK\baksmali.jar")) {
    Info "Downloading baksmali..."
    Invoke-WebRequest -Uri $BAKSMALI_URL -OutFile "$WORK\baksmali.jar" -UseBasicParsing
}
if (-not (Test-Path "$WORK\smali.jar")) {
    Info "Downloading smali..."
    Invoke-WebRequest -Uri $SMALI_URL -OutFile "$WORK\smali.jar" -UseBasicParsing
}
# Version-specific cache so switching -GadgetVersion re-downloads the right .so
$gadgetCached = "$WORK\libfrida-gadget-$GADGET_VERSION.so"
if (-not (Test-Path $gadgetCached)) {
    Info "Downloading frida-gadget $GADGET_VERSION..."
    Invoke-WebRequest -Uri $GADGET_URL -OutFile "$WORK\gadget-$GADGET_VERSION.so.xz" -UseBasicParsing
    # Decompress .xz via Python's lzma (no external tool dependency)
    $xzPath = "$WORK\gadget-$GADGET_VERSION.so.xz"
    $py = "import lzma,sys; open(sys.argv[2],'wb').write(lzma.open(sys.argv[1]).read())"
    python -c $py $xzPath $gadgetCached
    if (-not (Test-Path $gadgetCached)) {
        Die "Failed to extract gadget .xz for $GADGET_VERSION"
    }
}
Ok "Tools ready"

# -- STEP 3b: Extract target dex (InstagramMainActivity is in classes2.dex, confirmed v426) --

Info "Extracting classes2.dex..."
$targetDex = "classes2.dex"
$targetDexPath = "$WORK\probe_$targetDex"
$zip = [System.IO.Compression.ZipFile]::OpenRead("$WORK\instagram.apk")
$entry = $zip.GetEntry($targetDex)
$outStream = [System.IO.File]::Create($targetDexPath)
$entry.Open().CopyTo($outStream)
$outStream.Close()
$zip.Dispose()
Ok "Extracted: $targetDex"

# -- STEP 3c: Disassemble target dex, patch smali, reassemble --

$smaliOut   = "$WORK\smali_classes2"
$patchedDex = "$WORK\patched_$targetDex"

Info "Disassembling $targetDex with baksmali..."
if (-not (Test-Path $smaliOut)) {
    java -jar "$WORK\baksmali.jar" d $targetDexPath -o $smaliOut 2>&1 | Out-Null
} else {
    Ok "Using cached disassembly"
}

# Find the smali file for InstagramMainActivity
$smaliFile = Get-ChildItem $smaliOut -Recurse -Filter "InstagramMainActivity.smali" | Select-Object -First 1
if (-not $smaliFile) { Die "InstagramMainActivity.smali not found in disassembled dex" }

Info "Patching smali: $($smaliFile.FullName)"
$smali = Get-Content $smaliFile.FullName -Raw

# Check if already patched
if ($smali -match 'libfrida-gadget') {
    Ok "Already patched (libfrida-gadget call present)"
} else {
    # Inject into <clinit>. With on_load=resume the app boots normally and the gadget
    # listens for a Frida client; attach any time after boot. Classes are all loaded
    # by the time the user opens a DM, so dm-hooks.js needs no startup delay.
    $loadSnippet = '    const-string v0, "frida-gadget"' + "`n" + '    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V'

    # clinit may use .registers N (total) or .locals N — match either
    $clinitMatch = [regex]::Match($smali, '\.method[\w\s]+ constructor <clinit>\(\)V\s*\n\s*\.(locals|registers) (\d+)')
    if (-not $clinitMatch.Success) {
        Die "Could not find <clinit> with .locals/.registers in InstagramMainActivity.smali"
    }
    $regKeyword = $clinitMatch.Groups[1].Value   # "locals" or "registers"
    $regN = [int]$clinitMatch.Groups[2].Value
    # For static <clinit> with .registers N, all N registers are local (no params).
    # v0 is always available if N >= 1; bump only if N == 0 (extremely unlikely).
    if ($regN -lt 1) {
        $smali = [regex]::Replace($smali,
            '(\.method[\w\s]+ constructor <clinit>\(\)V\s*\n\s*\.' + $regKeyword + ' )\d+',
            ('$1' + 1))
    }
    $smali = [regex]::Replace($smali,
        '(\.method[\w\s]+ constructor <clinit>\(\)V[\s\S]*?\.' + $regKeyword + ' \d+)',
        ('$1' + "`n" + $loadSnippet))

    if (-not ($smali -match 'frida-gadget')) {
        Die "Regex injection failed -- check smali manually"
    }

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($smaliFile.FullName, $smali, $utf8NoBom)
    Ok "loadLibrary injected into <clinit>"
}

Info "Reassembling dex with smali..."
java -jar "$WORK\smali.jar" a $smaliOut -o $patchedDex 2>&1 | Out-Null
if (-not (Test-Path $patchedDex)) { Die "smali reassembly failed" }
Ok "Patched dex: $patchedDex"

# -- STEP 3d: Build patched APK (zip manipulation, no apktool) --

Info "Building patched APK..."
$patchedApk = "$WORK\instagram-gadget-unsigned.apk"
Copy-Item "$WORK\instagram.apk" $patchedApk -Force

# Open APK as zip and replace/add entries
$apkZip = [System.IO.Compression.ZipFile]::Open($patchedApk, "Update")

# 1. Replace the patched dex
$oldDexEntry = $apkZip.GetEntry($targetDex)
if ($oldDexEntry) { $oldDexEntry.Delete() }
$newDexEntry = $apkZip.CreateEntry($targetDex, "Optimal")
$dexStream   = $newDexEntry.Open()
$dexBytes    = [System.IO.File]::ReadAllBytes($patchedDex)
$dexStream.Write($dexBytes, 0, $dexBytes.Length)
$dexStream.Close()

$apkZip.Dispose()

# .so files must be STORED (not deflated) in the APK zip because Instagram uses
# extractNativeLibs=false. System.IO.Compression.CompressionLevel::NoCompression
# sets the compression algorithm to "deflate with level 0" which still has a deflate
# header — Android rejects it. We must use compression method 0 (STORED) instead.
# Fix: append the .so entries using raw zip byte manipulation via a Python one-liner
# which gives us exact control over the compression method field.

# on_load=resume: app boots normally instead of freezing at startup waiting for a
# client (wait caused Android to kill the frozen app -> "crash at startup"). The DM
# hooks fire on user interaction well after boot, so we don't need to pause at load.
$configJson = '{"interaction":{"type":"listen","address":"127.0.0.1","port":27042,"on_port_conflict":"pick-next","on_load":"resume"}}'
$configJsonPath = "$WORK\libfrida-gadget.config.so"
[System.IO.File]::WriteAllText($configJsonPath, $configJson, [System.Text.Encoding]::ASCII)

$pythonScript = @"
import zipfile, sys, os, shutil

apk    = sys.argv[1]
gadget = sys.argv[2]
config = sys.argv[3]
tmp    = apk + '.tmp'

SKIP = {'lib/arm64-v8a/libfrida-gadget.so', 'lib/arm64-v8a/libfrida-gadget.config.so'}

# Rebuild APK: copy all existing entries (preserving compression), then append .so STORED
with zipfile.ZipFile(apk, 'r') as src, \
     zipfile.ZipFile(tmp, 'w', allowZip64=True) as dst:
    for item in src.infolist():
        if item.filename in SKIP:
            continue
        dst.writestr(item, src.read(item.filename), compress_type=item.compress_type)
    dst.write(gadget, 'lib/arm64-v8a/libfrida-gadget.so',       compress_type=zipfile.ZIP_STORED)
    dst.write(config, 'lib/arm64-v8a/libfrida-gadget.config.so', compress_type=zipfile.ZIP_STORED)

os.replace(tmp, apk)
print('[+] .so files added as STORED (method=0)')
"@
$pyPath = "$WORK\add_stored.py"
[System.IO.File]::WriteAllText($pyPath, $pythonScript, [System.Text.Encoding]::ASCII)

python $pyPath $patchedApk $gadgetCached $configJsonPath

Ok "APK patched (no apktool rebuild)"

# -- STEP 4: Pull and convert Morphe.keystore --

Info "Pulling Morphe.keystore from device..."
adb pull $KEYSTORE_DEVICE_PATH "$WORK\Morphe.keystore" 2>&1 | Select-Object -Last 1
if (-not (Test-Path "$WORK\Morphe.keystore")) { Die "Could not pull Morphe.keystore" }
if (-not (Test-Path $BCPROV)) { Die "BouncyCastle JAR not found at: $BCPROV" }

# Store password is EMPTY — must pass via file because PowerShell drops "" args to native commands
$storePassFile = "$WORK\storepass.txt"
Set-Content -Path $storePassFile -Value "" -NoNewline -Encoding ASCII

Info "Converting BKS -> PKCS12..."
Remove-Item "$WORK\Morphe.p12" -ErrorAction SilentlyContinue
# keytool prints progress to stderr; redirect to stdout and ignore non-errors
# Use Continue (not Stop) so the NativeCommandError from progress lines doesn't abort
$prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
keytool -importkeystore `
    -srckeystore  "$WORK\Morphe.keystore" `
    -srcstoretype BKS `
    -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider `
    -providerpath  "$BCPROV" `
    -destkeystore  "$WORK\Morphe.p12" `
    -deststoretype PKCS12 `
    -srcstorepass:file $storePassFile `
    -deststorepass $KEYSTORE_PASS `
    -srcalias      $KEYSTORE_ALIAS `
    -destalias     $KEYSTORE_ALIAS `
    -srckeypass    $KEYSTORE_PASS `
    -destkeypass   $KEYSTORE_PASS `
    -noprompt 2>&1 | Where-Object { $_ -notmatch "^Warning" }
$ErrorActionPreference = $prev
if (-not (Test-Path "$WORK\Morphe.p12")) { Die "BKS -> PKCS12 conversion failed" }
Ok "Keystore converted"

# -- STEP 5: Zipalign + sign --

Info "Zipaligning..."
Remove-Item "$WORK\instagram-gadget-aligned.apk" -ErrorAction SilentlyContinue
& $ZIPALIGN -f 4 $patchedApk "$WORK\instagram-gadget-aligned.apk"

Info "Signing with Morphe key..."
& $APKSIGNER sign `
    --ks            "$WORK\Morphe.p12" `
    --ks-key-alias  $KEYSTORE_ALIAS `
    --ks-pass       "pass:$KEYSTORE_PASS" `
    --key-pass      "pass:$KEYSTORE_PASS" `
    --out           "$WORK\instagram-gadget.apk" `
    "$WORK\instagram-gadget-aligned.apk" 2>&1
$sizeMB = [math]::Round((Get-Item "$WORK\instagram-gadget.apk").Length / 1MB, 1)
Ok "Signed: $WORK\instagram-gadget.apk ($sizeMB MB)"

# -- STEP 6: Install --

Info "Installing on device (update, no data loss)..."
adb shell am force-stop $IG_PKG 2>$null
Start-Sleep -Seconds 1
$prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
adb install -r "$WORK\instagram-gadget.apk" 2>&1 | Select-Object -Last 3
$ErrorActionPreference = $prev

# -- STEP 7: Launch + port forward --

Info "Launching Instagram..."
adb shell monkey -p $IG_PKG -c android.intent.category.LAUNCHER 1 2>$null | Out-Null
Start-Sleep -Seconds 6

Info "Forwarding ports..."
adb forward "tcp:$GADGET_PORT_PRIMARY"  "tcp:$GADGET_PORT_PRIMARY"  2>$null | Out-Null
adb forward "tcp:$GADGET_PORT_FALLBACK" "tcp:$GADGET_PORT_FALLBACK" 2>$null | Out-Null

# Detect which port the main Instagram process bound to
$BOUND_PORT = $null
for ($i = 1; $i -le 10; $i++) {
    $line = adb logcat -d 2>$null | Select-String "Frida.*Listening on 127.0.0.1 TCP port"
    if ($line) {
        $ports = $line | ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { $_ -match '^\d+$' } | Sort-Object { [int]$_ }
        $BOUND_PORT = $ports | Select-Object -Last 1
        if ($BOUND_PORT) { break }
    }
    Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
if ($BOUND_PORT) {
    Ok "Gadget listening on port $BOUND_PORT"
    Write-Host "  Connect with:" -ForegroundColor White
    Write-Host "    frida -H 127.0.0.1:$BOUND_PORT Gadget -l scripts/frida/dm-hooks.js"
} else {
    Write-Host "  [~] Could not auto-detect port. Try:" -ForegroundColor Yellow
    Write-Host "    adb logcat | findstr `"Frida.*Listening`""
    Write-Host "    frida -H 127.0.0.1:$GADGET_PORT_FALLBACK Gadget -l scripts/frida/dm-hooks.js"
}
Write-Host ""
Write-Host "  After connecting:"
Write-Host "    1. Open a DM thread  -> Hook 1 fires (REST parseFromJson)"
Write-Host "    2. Receive a message -> Hook 2 fires (MQTT A0P)"
Write-Host "    3. Have someone unsend -> look for 'UNSENT' in output"
Write-Host "================================================" -ForegroundColor Cyan
