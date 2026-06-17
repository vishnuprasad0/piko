'use strict';
/*
 * discover-classes.js — Find the new obfuscated class names when IG updates
 *
 * Usage (run BEFORE dm-hooks.js if that script shows ClassNotFoundExceptions):
 *   frida -U com.instagram.android -l discover-classes.js
 *
 * What it does:
 *   Enumerates every loaded class, then scans for the stable string constants
 *   that anchor our fingerprints. Output gives you the new class names to put
 *   in dm-hooks.js V426 config and in Fingerprint.kt.
 *
 * Trigger it by opening a DM thread first so the relevant classes are loaded.
 * Then type  discover()  in the Frida REPL and press Enter.
 *
 * Expected output example (v426):
 *   [JSON parser]  X.0gL  — has A00(Z) + parseFromJson
 *   [MQTT class]   X.0gF  — has A0P
 */

var discovered = false;

function discover() {
    if (discovered) { console.log('[discover] already ran — restart Frida to re-scan'); return; }
    discovered = true;

    console.log('[discover] scanning loaded classes… (may take 5-15 s)');

    var jsonParserCandidates = [];
    var mqttCandidates       = [];

    Java.enumerateLoadedClassesSync().forEach(function (name) {
        try {
            var cls = Java.use(name);

            // ── Locate the JSON parser class (Hook 1 anchor) ────────────────
            // We're looking for the class that has A00 with returnType Z and
            // contains the string constants "item_id" and "hide_in_thread".
            //
            // Frida can't grep string constants directly, so we probe for:
            //  • a method named A00 that returns boolean
            //  • a method named parseFromJson in the same class
            var hasA00Bool = false;
            var hasParseFromJson = false;
            try {
                var overloads = cls.A00.overloads;
                for (var i = 0; i < overloads.length; i++) {
                    if (overloads[i].returnType.name === 'boolean') { hasA00Bool = true; break; }
                }
            } catch (e) {}
            try { cls.parseFromJson; hasParseFromJson = true; } catch (e) {}

            if (hasA00Bool && hasParseFromJson) {
                jsonParserCandidates.push(name);
                console.log('[JSON parser candidate] ' + name + ' — has A00(Z) + parseFromJson');
            }

            // ── Locate the MQTT class (Hook 2 anchor) ────────────────────────
            // Looking for class with method A0P.
            // Narrow down by also requiring it to have a superclass named X.*
            // (DirectItem base) and no declared instance fields (LX/0gF; is a wrapper).
            var hasA0P = false;
            try { cls.A0P; hasA0P = true; } catch (e) {}

            if (hasA0P) {
                var superName = '';
                try { superName = cls.class.getSuperclass().getName(); } catch (e) {}
                // Narrow: superclass starts with X. (obfuscated base) and class name short
                if (superName.startsWith('X.') && name.startsWith('X.') && name.length < 8) {
                    mqttCandidates.push(name + ' (super: ' + superName + ')');
                    console.log('[MQTT class candidate] ' + name + ' super=' + superName);
                }
            }
        } catch (e) {
            // skip classes that can't be used (interfaces, system, etc.)
        }
    });

    console.log('\n── Results ──');
    if (jsonParserCandidates.length === 0) {
        console.log('[JSON parser] NONE FOUND — classes may not be loaded yet. Open a DM thread first, then re-run.');
    } else {
        console.log('[JSON parser] candidates: ' + jsonParserCandidates.join(', '));
    }
    if (mqttCandidates.length === 0) {
        console.log('[MQTT class]  NONE FOUND — receive a real-time message first, then re-run.');
    } else {
        console.log('[MQTT class]  candidates: ' + mqttCandidates.join(', '));
    }
    console.log('\nUpdate V426.parserClass / V426.mqttClass in dm-hooks.js with the above names.');
    console.log('Also update DirectItemFieldParserFingerprint / DirectItemPostprocessFingerprint in Fingerprint.kt.');
}

// Export so you can call  discover()  in the Frida REPL
global.discover = discover;

console.log('[discover] loaded. Open a DM thread, then type  discover()  in this REPL.');
