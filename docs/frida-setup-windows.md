# Frida Setup for Windows

Frida Gadget setup for Instagram on a **non-rooted** device, driven entirely from
Windows. Verified working on a Moto device (MediaTek mt6855, Android 15) in Jun 2026.

## TL;DR

```powershell
# 1. Install tooling (once). Match the frida CLI to the gadget version.
pip install "frida==17.14.1" frida-tools

# 2. Patch + sign + install Instagram with the embedded gadget (no apktool needed)
powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget.ps1

# 3. Launch IG (script does this), forward the port, attach
adb forward tcp:27043 tcp:27043
frida -H 127.0.0.1:27043 Gadget -l scripts\frida\dm-hooks.js
```

Then have another account **send + unsend** a DM and watch for
`*** UNSEND / DELETION detected (A1Y=true) ***`.

## How the gadget pipeline works (no apktool)

`setup-gadget.ps1` deliberately avoids `objection`/`apktool` because apktool **hangs**
on Windows while recompiling Instagram's resources. Instead it:

1. Pulls the Instagram APK and extracts only `classes2.dex` (contains `InstagramMainActivity`).
2. Disassembles that one dex with **baksmali**, injects
   `System.loadLibrary("frida-gadget")` into `<clinit>`, reassembles with **smali**.
3. Rebuilds the APK zip in place (Python `zipfile`): replaces `classes2.dex` and adds
   `libfrida-gadget.so` + `libfrida-gadget.config.so` as **STORED** (uncompressed —
   required because IG uses `extractNativeLibs=false`).
4. Converts the device's **BKS** `Morphe.keystore` → PKCS12 (BouncyCastle JAR bundled
   with Android cmdline-tools) and signs with apksigner.
5. `adb install -r` (no uninstall — the Morphe key matches the installed build).

Gadget config uses `on_load=resume` so the app **boots normally** (an earlier
`on_load=wait` froze the app at startup, which Android killed → looked like a crash).

## Keystore specifics (Morphe)

- `Morphe.keystore` is **BKS** format at `/storage/emulated/0/Download/Morphe.keystore`.
- **Store password is EMPTY**; alias and key password are both `Morphe`.
- In PowerShell the empty store password must be passed via a file
  (`-srcstorepass:file storepass.txt`) — PowerShell drops a literal `""` arg to native
  commands, which yields "KeyStore integrity check failed".

## MediaTek / Frida version compatibility (IMPORTANT)

Frida's Java bridge interops with the device's ART. On MediaTek this is **per-chip**:

| Device | Working gadget | SIGILLs on |
|--------|----------------|------------|
| MediaTek **mt6855**, Android 15 | **17.14.1** | 17.9.3 |
| Moto g64 5G (MTK) — prior note | 17.9.3 | 17.14.0 |

Symptom of a bad version: `Error: illegal instruction` in `/frida/bridges/java.js`
during class `build` on the first `Java.use()`. Fix: sweep versions —
`setup-gadget.ps1 -GadgetVersion <X>` and `pip install "frida==<X>"` to match.

## Hook stability on MediaTek

- **`X.0gF.A0P` (MQTT realtime) — STABLE.** Default-on. This is where realtime DM and
  unsend deltas flow; `dm-hooks.js` dumps an item only when `A1Y=true` (deletion).
- **`X.0gL.parseFromJson` (REST) — UNSTABLE on MediaTek.** Hooking it SIGSEGVs IG's own
  worker threads within ~20s. Default-off (`ENABLE_REST_HOOK=false` in `dm-hooks.js`).
  Enable it only on non-MediaTek devices / emulators.

A control run (gadget connected, **zero** hooks) is completely stable, so the
instability is specifically Frida's Interceptor on that hot parser — not the gadget.

## Re-attach without reinstalling

```powershell
adb shell am force-stop com.instagram.android
adb shell monkey -p com.instagram.android -c android.intent.category.LAUNCHER 1
adb forward tcp:27043 tcp:27043
frida -H 127.0.0.1:27043 Gadget -l scripts\frida\dm-hooks.js
```

## Emulator alternative (rooted)

`scripts\frida\setup-gadget-rooted.ps1` uses frida-server (no APK patching) for the
`Pixel_9a` AVD or any rooted device. Caveat: the AVD is x86_64 while the pulled
Instagram APK is arm64 — needs an ARM-translation image or an x86 IG build to run.

## Files

- `scripts/frida/setup-gadget.ps1`        — Windows, non-rooted (no apktool). `-GadgetVersion` param.
- `scripts/frida/setup-gadget.sh`         — Mac/Linux equivalent.
- `scripts/frida/setup-gadget-rooted.ps1` — Windows, rooted device / emulator (frida-server).
- `scripts/frida/dm-hooks.js`             — hooks; `ENABLE_REST_HOOK` / `ENABLE_MQTT_HOOK` toggles.
- `scripts/frida/run-hooks.py`            — keeps a session resident for N seconds (note: needs
                                            the frida CLI's bundled Java bridge; raw create_script
                                            lacks the `Java` global in Frida 17).

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `Error: illegal instruction` at `Java.use` | Wrong gadget version for this chip — sweep `-GadgetVersion`. |
| `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries` | `.so` not STORED — the script adds them uncompressed via Python zipfile. |
| `KeyStore integrity check failed` | Empty store pass dropped by PowerShell — pass via `-srcstorepass:file`. |
| App frozen at startup | `on_load=wait` in gadget config — script uses `resume`. |
| SIGSEGV on `IgExecutorV2` after ~20s | REST `parseFromJson` hook on MediaTek — keep `ENABLE_REST_HOOK=false`. |
| `'Java' is not defined` in a Python harness | Raw `create_script` has no Java bridge in Frida 17 — use the frida CLI. |
