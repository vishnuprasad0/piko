'use strict';
/*
 * sg-probe.js — Frida recon for the E2EE secure-messaging path (Fix B).
 *
 * WHY: Static RE of IG v426/v430 found that modern (E2EE) DMs use the clean,
 * UNOBFUSCATED proto model `com.meta.communicate.SgMessage`:
 *     msgId_:String  text_:String  senderId_:String  timestampMs_:J  isUnsent_:Z
 * `isUnsent_ == true` is the deletion signal. This probe confirms the fields live
 * AND finds the exact CONSUMER method (the IG method that reads/acts on an unsent
 * SgMessage) so the smali patch can hook a stable, named anchor instead of the
 * obfuscated X.0gF fields.
 *
 * USAGE (non-rooted gadget, Moto g64 5G — use gadget+CLI 17.9.3):
 *   powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget.ps1 -GadgetVersion 17.9.3
 *   adb forward tcp:27042 tcp:27042
 *   frida -H 127.0.0.1:27042 Gadget -l scripts/frida/sg-probe.js
 *
 * THEN from a SECOND account: send a message, then UNSEND it. Watch for
 *   *** SgMessage UNSENT ***  followed by a Java backtrace = the consumer method.
 *
 * The backtrace's top non-proto, non-Frida frame is the Fix B hook anchor —
 * paste it back so the fingerprint can be written.
 */

var SG_CLASS = 'com.meta.communicate.SgMessage';

// Re-entrancy guard (same rationale as dm-hooks.js): never let our logging code
// re-enter a hooked method on the same JS stack — corrupts ART JNI state on MTK.
var BUSY = false;

// Dedupe so inbox re-syncs don't reprint the same message. Key = msgId or sender:ts.
var SEEN = {};
// Capture the consumer backtrace only a few times to avoid log spam / instability.
var BT_CAP = 5, btCount = 0;

function readStr(obj, name) {
    try {
        var f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        var v = f.get(obj);
        return v === null ? null : '' + v;
    } catch (e) { return undefined; }
}

function dumpSg(tag, sg) {
    if (BUSY) return; BUSY = true;
    try {
        var id     = readStr(sg, 'msgId_');
        var sender = readStr(sg, 'senderId_');
        var ts     = readStr(sg, 'timestampMs_');
        var text   = readStr(sg, 'text_');
        var unsent = readStr(sg, 'isUnsent_');
        console.log('[' + tag + '] id=' + id + ' sender=' + sender +
                    ' ts=' + ts + ' unsent=' + unsent +
                    ' text=' + (text === null ? 'null' : JSON.stringify(text)));
        return { id: id, sender: sender, ts: ts, unsent: unsent === 'true' };
    } catch (e) { console.log('[!] dumpSg: ' + e); return null; }
    finally { BUSY = false; }
}

function backtrace() {
    try {
        var Exception = Java.use('java.lang.Exception');
        var Log = Java.use('android.util.Log');
        return Log.getStackTraceString(Exception.$new());
    } catch (e) { return '<backtrace failed: ' + e + '>'; }
}

// MediaTek Java-bridge SIGILL is a race during class `build` (JIT running on other
// threads). It propagates out of Java.perform itself, so wrap the whole call and
// retry after a short idle delay — same pattern as dm-hooks.js installHooks().
var INSTALL_ATTEMPTS = 10;
function start(attempt) {
    try {
        Java.perform(doProbe);
    } catch (e) {
        var msg = '' + e;
        if (msg.indexOf('illegal instruction') >= 0 && attempt < INSTALL_ATTEMPTS) {
            console.log('[retry] Java bridge SIGILL; retry ' + attempt + '/' +
                        INSTALL_ATTEMPTS + ' in 1.2s (keep the app idle)...');
            setTimeout(function () { start(attempt + 1); }, 1200);
        } else {
            console.log('[!] probe install failed (attempt ' + attempt + '): ' + e);
            console.log('    Relaunch IG fresh and re-attach before touching the app.');
        }
    }
}

function doProbe() {
    console.log('\n[sg-probe] init — probing ' + SG_CLASS + '\n');

    var Sg;
    try {
        Sg = Java.use(SG_CLASS);
    } catch (e) {
        console.log('[-] ' + SG_CLASS + ' not found: ' + e);
        console.log('    Run discover-classes.js — the E2EE proto class may have moved.');
        return;
    }
    console.log('[+] ' + SG_CLASS + ' resolved');

    // 1) Enumerate methods once so we can see getters / consumers available.
    try {
        var methods = Sg.class.getDeclaredMethods();
        console.log('[i] SgMessage declares ' + methods.length + ' methods (showing unsent/id/text ones):');
        for (var i = 0; i < methods.length; i++) {
            var ms = '' + methods[i];
            if (/Unsent|MsgId|getText|getSenderId|getTimestamp/i.test(ms)) console.log('    ' + ms);
        }
    } catch (e) { console.log('[!] method enum: ' + e); }

    // 2) Hook the unsent getter if present — its CALLER (backtrace) is the consumer
    //    we want to anchor Fix B on. Only act when it returns true (a real unsend).
    var hookedGetter = false;
    ['getIsUnsent', 'isUnsent'].forEach(function (gName) {
        if (hookedGetter) return;
        try {
            if (!Sg[gName]) return;
            Sg[gName].overloads.forEach(function (ov) {
                ov.implementation = function () {
                    var r = ov.apply(this, arguments);
                    try {
                        if (r === true && btCount < BT_CAP) {
                            var info = dumpSg('SgMessage UNSENT (via ' + gName + ')', this);
                            var key = (info && info.id) || (info && (info.sender + ':' + info.ts));
                            if (key && !SEEN[key]) {
                                SEEN[key] = true;
                                btCount++;
                                console.log('\n*** SgMessage UNSENT *** consumer backtrace #' + btCount + ':');
                                console.log(backtrace());
                                console.log('*** (top non-SgMessage / non-Frida frame = Fix B hook anchor) ***\n');
                            }
                        }
                    } catch (e) {}
                    return r;
                };
            });
            hookedGetter = true;
            console.log('[+] hooked ' + SG_CLASS + '.' + gName + '() — will backtrace on true');
        } catch (e) { console.log('[-] could not hook ' + gName + ': ' + e); }
    });
    if (!hookedGetter) {
        console.log('[i] No isUnsent getter to hook; will rely on the field-scan path below.');
    }

    // 3) Fallback: hook the proto parse/build entry so every SgMessage instance is
    //    dumped as it is created. Proto Lite classes expose dynamicMethod/newBuilder;
    //    we try a few common names. Dump only flags unsent ones loudly.
    ['newBuilder', 'parseFrom', 'build'].forEach(function (mName) {
        try {
            if (!Sg[mName]) return;
            Sg[mName].overloads.forEach(function (ov) {
                ov.implementation = function () {
                    var r = ov.apply(this, arguments);
                    try {
                        if (r && r.getClass && r.getClass().getName() === SG_CLASS) {
                            var info = dumpSg('SgMessage.' + mName, r);
                            if (info && info.unsent) {
                                var key = info.id || (info.sender + ':' + info.ts);
                                if (key && !SEEN[key]) {
                                    SEEN[key] = true;
                                    console.log('   <<< UNSENT via ' + mName);
                                }
                            }
                        }
                    } catch (e) {}
                    return r;
                };
            });
            console.log('[+] hooked ' + SG_CLASS + '.' + mName + '()');
        } catch (e) { /* method absent — fine */ }
    });

    console.log('\n[sg-probe] armed. From a SECOND account: send a msg, then UNSEND it.');
    console.log('[sg-probe] Watch for "*** SgMessage UNSENT ***" + backtrace.\n');
}

// kick off with SIGILL retry
start(1);
