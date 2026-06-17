'use strict';
/*
 * dm-hooks.js — Frida real-time verification for piko "Save deleted messages"
 *
 * ── QUICK START ─────────────────────────────────────────────────────────────
 *
 * OPTION A — Rooted device or Android emulator (easiest):
 *   1. Push frida-server to device and start it:
 *        adb push frida-server /data/local/tmp/
 *        adb shell "chmod +x /data/local/tmp/frida-server && /data/local/tmp/frida-server &"
 *   2. Attach and run:
 *        frida -U -f com.instagram.android -l dm-hooks.js --no-pause
 *      (or if Instagram is already running)
 *        frida -U com.instagram.android -l dm-hooks.js
 *
 * OPTION B — Non-rooted device (Frida Gadget embedded in APK):
 *   1. pip install objection
 *   2. objection patchapk -s Instagram-piko-patched.apk
 *      (this produces Instagram-piko-patched.objection.apk — install that)
 *   3. Launch Instagram on device, then:
 *        frida -U gadget -l dm-hooks.js
 *
 * ── WHAT TO DO AFTER ATTACHING ──────────────────────────────────────────────
 *
 *  1. Open any DM thread → Hook 1 should fire (REST thread-history load)
 *  2. Receive a new message in real-time → Hook 2 should fire (MQTT)
 *  3. Have another account unsend a message → look for A1Y = true in the dump
 *
 * ── HOW TO READ OUTPUT ──────────────────────────────────────────────────────
 *
 *  [+] Hook N installed         → class + method found, hook active
 *  [-] Hook N FAILED: <err>     → class/method name changed since v426 — needs RE
 *  [Hook1-REST] fired           → parseFromJson called (REST path working)
 *  [Hook2-MQTT] fired           → A0P called (real-time MQTT path working)
 *  ◄ UNSENT                     → hide_in_thread field is true on this item
 *  ◄ item_id                    → the item_id field (confirm A13 is correct)
 *
 * ── UPDATE FOR NEW VERSION ───────────────────────────────────────────────────
 *
 *  If you see "[-] Hook N FAILED: ClassNotFoundException":
 *    Run discover-classes.js instead — it scans all loaded classes for the
 *    key string constants and prints the new obfuscated class names.
 *
 * ── smali name → Frida name conversion ──────────────────────────────────────
 *  LX/0gL;  → Java class name:  X.0gL    (drop L prefix + ; suffix, / → .)
 *  LX/0gF;  → X.0gF
 *  LX/9ZA;  → X.9ZA
 */

// ── v426 class + field config ─────────────────────────────────────────────────
// Update these when IG obfuscation changes (run discover-classes.js to find new names)
const V426 = {
    // LX/0gL; — JSON parser class (has A00 field-dispatcher + parseFromJson)
    parserClass: 'X.0gL',
    // LX/0gF; — MQTT item class (has A0P post-processor)
    mqttClass:   'X.0gF',
    // v426 field names on LX/9ZA; (DirectItem base)
    fields: {
        item_id:        'A13',
        hide_in_thread: 'A1Y',  // true = unsent
        user_id:        'A1M',
        timestamp:      'A1J',
        text_content:   'A1I',
        item_type:      'A0Y',
        thread_key:     'A0W',  // DirectThreadKey object; .A00 = threadId string
    },
};

// ── helpers ───────────────────────────────────────────────────────────────────

var DUMPED = {};

/**
 * Dump every declared field on obj, walking the full superclass chain.
 * Marks hide_in_thread when true, and item_id for identification.
 */
function dumpObject(label, javaObj) {
    if (!javaObj) { console.log(label + ': null'); return; }
    var runtimeClass = javaObj.getClass();
    var key = runtimeClass.getName();
    console.log('\n═══ ' + label + ' :: ' + key + ' ═══');
    var cls = runtimeClass;
    while (cls && cls.getName() !== 'java.lang.Object') {
        if (cls.getName() !== runtimeClass.getName()) {
            console.log('  ── [inherited: ' + cls.getName() + '] ──');
        }
        var declared = cls.getDeclaredFields();
        for (var i = 0; i < declared.length; i++) {
            var f = declared[i];
            f.setAccessible(true);
            var val, vs;
            try {
                val = f.get(javaObj);
                vs  = (val === null) ? 'null' : val.toString();
                if (vs.length > 120) vs = vs.substring(0, 120) + '…';
            } catch (e) {
                vs = '<inaccessible: ' + e + '>';
            }
            var marker = '';
            if (f.getName() === V426.fields.hide_in_thread && vs === 'true') marker = '  ◄ UNSENT!';
            if (f.getName() === V426.fields.item_id)        marker = '  ◄ item_id';
            if (f.getName() === V426.fields.thread_key)     marker = '  ◄ thread_key';
            if (f.getName() === V426.fields.text_content)   marker = '  ◄ text';
            console.log('  ' + f.getType().getSimpleName() + ' ' + f.getName() + ' = ' + vs + marker);

            // For action_log items (A1Y=true), the A0o field on X.0gF holds an object
            // (type X.3jS) that likely references the original deleted item_id.
            // Safe deep-dump: only String fields, no recursive getClass() calls.
            if (f.getName() === 'A0o' && val !== null && !(vs.startsWith('null'))) {
                try {
                    var innerCls = val.getClass();
                    var cname = innerCls.getName();
                    // Only inspect known obfuscated X.* classes, avoid system objects
                    if (cname.length < 8 && cname.startsWith('X.')) {
                        console.log('  ↳ A0o fields (' + cname + '):');
                        while (innerCls && innerCls.getName() !== 'java.lang.Object') {
                            var iFields = innerCls.getDeclaredFields();
                            for (var j = 0; j < iFields.length; j++) {
                                var fi = iFields[j];
                                fi.setAccessible(true);
                                try {
                                    var iv = fi.get(val);
                                    var ivs = (iv === null) ? 'null' : iv.toString();
                                    if (ivs.length > 120) ivs = ivs.substring(0, 120) + '…';
                                    var imark = (ivs.match && ivs.match(/^\d{30,}$/)) ? '  ◄ item_id ref?' : '';
                                    console.log('      ' + fi.getType().getSimpleName() + ' ' + fi.getName() + ' = ' + ivs + imark);
                                } catch (e2) {}
                            }
                            innerCls = innerCls.getSuperclass();
                        }
                    }
                } catch (e) { /* skip if getClass fails on native-backed object */ }
            }
        }
        cls = cls.getSuperclass();
    }
    console.log('═══\n');
}

// ── hook 1: REST path ─────────────────────────────────────────────────────────
// LX/0gL;.parseFromJson(LX/R0r;)LX/9ZA;
// Fires when Instagram fetches thread history from the REST API.
// This is what DirectItemFieldParserFingerprint + Hook 1 in the smali patch targets.

Java.perform(function () {
    console.log('\n[piko] dm-hooks.js initialising for Instagram v426…\n');

    // Hook 1 ─ REST parseFromJson
    try {
        var ParserClass = Java.use(V426.parserClass);
        ParserClass.parseFromJson.implementation = function (reader) {
            var result = this.parseFromJson(reader);
            console.log('[Hook1-REST] parseFromJson → ' + (result ? result.getClass().getName() : 'null'));
            if (result) dumpObject('Hook1 DirectItem', result);
            return result;
        };
        console.log('[+] Hook 1 installed (' + V426.parserClass + '.parseFromJson)');
    } catch (e) {
        console.log('[-] Hook 1 FAILED (' + V426.parserClass + '.parseFromJson): ' + e);
        console.log('    Update V426.parserClass — run discover-classes.js to find new name');
    }

    // Hook 1b ─ A00 field dispatcher (proves the fingerprint anchor still exists)
    // This is the method DirectItemFieldParserFingerprint locates via strings ["item_id","hide_in_thread"].
    try {
        var ParserClass2 = Java.use(V426.parserClass);
        // A00 has multiple overloads; target the one with returnType Z (boolean)
        var a00Hooked = false;
        ParserClass2.A00.overloads.forEach(function (ov) {
            if (ov.returnType.name === 'boolean') {
                ov.implementation = function () {
                    var key = arguments[arguments.length - 1]; // last arg = JSON key String
                    if (key === 'item_id' || key === 'hide_in_thread') {
                        console.log('[Hook1b-A00] dispatched JSON field: ' + key);
                    }
                    return ov.apply(this, arguments);
                };
                a00Hooked = true;
            }
        });
        if (a00Hooked) {
            console.log('[+] Hook 1b installed (' + V426.parserClass + '.A00 Z-overload)');
        } else {
            console.log('[-] Hook 1b: no boolean-return A00 overload found on ' + V426.parserClass);
        }
    } catch (e) {
        console.log('[-] Hook 1b (A00 probe) FAILED: ' + e);
    }

    // Hook 2 ─ MQTT A0P
    // LX/0gF;.A0P(UserSession, LX/02L;)LX/0gF;
    // Fires for every real-time DM delivered via MQTT/MSys sync.
    // This is what DirectItemPostprocessFingerprint + Hook 2 targets.
    try {
        var MqttClass = Java.use(V426.mqttClass);
        var overloads = MqttClass.A0P.overloads;
        if (!overloads || overloads.length === 0) throw new Error('A0P has no overloads');
        overloads.forEach(function (ov) {
            ov.implementation = function () {
                var result = ov.apply(this, arguments);
                if (result !== null) {
                    console.log('[Hook2-MQTT] A0P success → ' + result.getClass().getName());
                    dumpObject('Hook2 DirectItem', result);
                } else {
                    // Null returns are error paths inside A0P — log but don't dump
                    console.log('[Hook2-MQTT] A0P returned null (error/early-exit path)');
                }
                return result;
            };
        });
        console.log('[+] Hook 2 installed (' + V426.mqttClass + '.A0P, ' + overloads.length + ' overload(s))');
    } catch (e) {
        console.log('[-] Hook 2 FAILED (' + V426.mqttClass + '.A0P): ' + e);
        console.log('    Update V426.mqttClass — grep classes12.dex for "DirectMessage.postprocess"');
    }

    console.log('\n[piko] Ready. Open a DM thread → Hook 1 should fire.');
    console.log('[piko] Send + unsend from another account → Hook 2 + look for ◄ UNSENT\n');
});
