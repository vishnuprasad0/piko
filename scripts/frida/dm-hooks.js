'use strict';
/*
 * dm-hooks.js — Frida real-time verification for piko "Save deleted messages"
 *
 * ── QUICK START ─────────────────────────────────────────────────────────────
 *
 * OPTION A — Rooted device or Android emulator (EASIEST, all platforms):
 *   Mac/Windows: bash scripts/frida/setup-gadget-rooted.sh (or .ps1 on Windows)
 *   Then:
 *        frida -U -f com.instagram.android -l dm-hooks.js --no-pause
 *      (or if Instagram is already running)
 *        frida -U com.instagram.android -l dm-hooks.js
 *
 * OPTION B — Non-rooted device (Frida Gadget embedded in APK):
 *   Mac:     bash scripts/frida/setup-gadget.sh
 *   Windows: powershell -ExecutionPolicy Bypass -File scripts\frida\setup-gadget.ps1
 *   Then launch Instagram on device and connect:
 *        frida -H 127.0.0.1:27043 Gadget -l scripts/frida/dm-hooks.js
 *
 * NOTE: Windows users — if gadget setup hangs during APK rebuild, use OPTION A
 * (rooted emulator) instead. It's simpler and faster anyway.
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

// ── hook toggles ────────────────────────────────────────────────────────────
// On MediaTek (e.g. mt6855 / Android 15) Frida's Interceptor on the REST parser
// X.0gL.parseFromJson destabilizes ART and SIGSEGVs unrelated worker threads
// (IgExecutorV2) within ~20s. The MQTT hook X.0gF.A0P is stable on the same device
// and is where realtime DM + unsend deltas actually flow. So REST is OFF by default.
//   - Set ENABLE_REST_HOOK = true only on devices where parseFromJson hooking is
//     stable (most non-MediaTek / Qualcomm devices and emulators).
// CONFIRMED LIVE on mt6855: A0P (MQTT) catches only NEW incoming items, NOT remote
// unsends (sent Test123 -> [LIVE MSG]; unsent it -> nothing). REST parseFromJson MIGHT
// catch unsend deltas but crashes MediaTek before it can. Both hooks are left here for
// non-MediaTek devices; on MediaTek the live approach is unreliable — see docs.
var ENABLE_REST_HOOK = false;   // X.0gL.parseFromJson — UNSTABLE on MediaTek
var ENABLE_MQTT_HOOK = true;    // X.0gF.A0P — stable, but only sees NEW items not unsends

// Only process items from this sender user_id (A1M). Empty = all users. A filter cuts
// per-item work, but it also hides backlog from other senders — leave EMPTY when you
// want to sample the A0o structure from historical unsent items. Example: '50803907937'
var USER_FILTER = '';

// How many historical (backlog) unsent items to fully dump (incl. A0o/X.3jS contents)
// right after attach. This captures the deleted-message payload layout in the first
// seconds, before the MediaTek ~1-min Frida instability — no live unsend required.
var A0O_SAMPLE_CAP = 3;
var a0oSamples = 0;

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

// Re-entrancy guard. The hooks fire on Instagram's own threads; if our dump code
// calls a Java method (e.g. toString) that synchronously re-invokes a hooked method,
// Frida re-enters our implementation on the same JS stack -> ART JNI state corrupts
// -> SIGSEGV/SIGILL. This boolean blocks the dump from running while already dumping.
var BUSY = false;

// Scalar Java types we can safely call toString() on. Calling toString() on ANY
// other (complex Instagram) object is what triggered the crash-on-fire: it can run
// arbitrary lazy-init / native code and re-enter the hooks. For complex objects we
// print only the class name, never their contents.
var SAFE_TYPES = {
    'java.lang.String': 1, 'boolean': 1, 'java.lang.Boolean': 1,
    'int': 1, 'long': 1, 'short': 1, 'byte': 1, 'char': 1,
    'java.lang.Integer': 1, 'java.lang.Long': 1, 'java.lang.Short': 1,
    'float': 1, 'double': 1, 'java.lang.Float': 1, 'java.lang.Double': 1,
};

// ── cached field access (hot-path) ────────────────────────────────────────────
// safeField() walks the superclass chain calling getDeclaredField on EVERY call.
// On the hot A0P MQTT thread that sustained reflection destabilizes ART on MediaTek
// (the app freezes/SIGABRTs after a while). Since every A0P item is the same class
// (X.0gF), we resolve each Field handle ONCE and reuse it — turning per-call work
// into a single light Field.get(). Cache key = field name.
var FIELD_CACHE = {};   // "className:name" -> { field, scalar } | { field: null }

function cachedField(rc, name) {
    var ck;
    try { ck = rc.getName() + ':' + name; } catch (e) { ck = '?:' + name; }
    if (Object.prototype.hasOwnProperty.call(FIELD_CACHE, ck)) return FIELD_CACHE[ck];
    var c = rc, found = null;
    while (c && c.getName() !== 'java.lang.Object') {
        try { found = c.getDeclaredField(name); } catch (e) { found = null; }
        if (found) { try { found.setAccessible(true); } catch (e) {} break; }
        try { c = c.getSuperclass(); } catch (e) { break; }
    }
    var scalar = false;
    if (found) { try { scalar = !!SAFE_TYPES[found.getType().getName()]; } catch (e) {} }
    FIELD_CACHE[ck] = { field: found, scalar: scalar };
    return FIELD_CACHE[ck];
}

// Light field read using the cache. Returns string, or undefined if field absent.
function readField(obj, rc, name) {
    var c = cachedField(rc, name);
    if (!c.field) return undefined;
    var v; try { v = c.field.get(obj); } catch (e) { return '<inaccessible>'; }
    if (v === null || v === undefined) return 'null';
    if (c.scalar) { try { return '' + v.toString(); } catch (e) { return '<toString failed>'; } }
    try { return '<' + v.getClass().getName() + '>'; } catch (e) { return '<obj>'; }
}

/**
 * Read a single field BY NAME, walking the superclass chain to find where it's
 * declared. Returns a printable string, or undefined if the field doesn't exist.
 * Never calls toString() on complex objects — only on scalar types.
 */
function safeField(javaObj, runtimeClass, fieldName) {
    var cls = runtimeClass;
    while (cls && cls.getName() !== 'java.lang.Object') {
        var f = null;
        try { f = cls.getDeclaredField(fieldName); } catch (e) { f = null; }
        if (f) {
            try { f.setAccessible(true); } catch (e) {}
            var typeName;
            try { typeName = f.getType().getName(); } catch (e) { typeName = '?'; }
            var val;
            try { val = f.get(javaObj); } catch (e) { return '<inaccessible>'; }
            if (val === null || val === undefined) return 'null';
            if (SAFE_TYPES[typeName]) {
                try { return '' + val.toString(); } catch (e) { return '<toString failed>'; }
            }
            // Complex object: report its runtime class ONLY — do not stringify contents.
            try { return '<' + val.getClass().getName() + '>'; } catch (e) { return '<obj ' + typeName + '>'; }
        }
        try { cls = cls.getSuperclass(); } catch (e) { break; }
    }
    return undefined; // not declared anywhere in the chain
}

/**
 * Safe DirectItem dump: reads only the known v426 fields by name. No full-chain
 * field walk, no toString() on complex objects, guarded against re-entrancy.
 */
function dumpObject(label, javaObj) {
    if (BUSY) return;          // skip nested dumps triggered by re-entrant hook fire
    BUSY = true;
    try {
        if (!javaObj) { console.log(label + ': null'); return; }
        var rc;
        try { rc = javaObj.getClass(); } catch (e) {
            console.log('[' + label + '] <native-backed obj, cannot inspect>');
            return;
        }
        console.log('\n=== ' + label + ' :: ' + rc.getName() + ' ===');

        var F = V426.fields;
        var rows = [
            ['item_id',        F.item_id],
            ['hide_in_thread', F.hide_in_thread],
            ['user_id',        F.user_id],
            ['timestamp',      F.timestamp],
            ['text (REST)',    F.text_content],
            ['item_type',      F.item_type],
            ['thread_key',     F.thread_key],
            ['A0o (MQTT text)', 'A0o'],
        ];
        for (var i = 0; i < rows.length; i++) {
            var name = rows[i][1];
            var v = safeField(javaObj, rc, name);
            if (v === undefined) continue;          // field not present on this item
            var mark = '';
            if (name === F.hide_in_thread && v === 'true') mark = '   <<< UNSENT (deletion signal)';
            console.log('  ' + name + ' [' + rows[i][0] + '] = ' + v + mark);
        }
        console.log('===\n');
    } catch (e) {
        console.log('[!] dump error: ' + e);
    } finally {
        BUSY = false;
    }
}

/**
 * Shallow-dump only the SCALAR fields of an object (String/primitive), walking the
 * superclass chain. Safe: never toString()s complex objects, never recurses. Used to
 * peek inside the A0o wrapper (X.3jS) to recover the deleted item's id / text.
 */
function dumpScalars(label, obj) {
    if (!obj) { console.log('  ' + label + ' = null'); return; }
    var rc; try { rc = obj.getClass(); } catch (e) { console.log('  ' + label + ' <native>'); return; }
    console.log('  ' + label + ' (' + rc.getName() + '):');
    var c = rc;
    while (c && c.getName() !== 'java.lang.Object') {
        var fs; try { fs = c.getDeclaredFields(); } catch (e) { break; }
        for (var i = 0; i < fs.length; i++) {
            var f = fs[i];
            try { f.setAccessible(true); } catch (e) {}
            var t; try { t = f.getType().getName(); } catch (e) { t = '?'; }
            if (!SAFE_TYPES[t]) continue;            // scalars only
            var v; try { v = f.get(obj); } catch (e) { continue; }
            if (v === null) continue;
            var s; try { s = '' + v.toString(); } catch (e) { continue; }
            if (s.length > 100) s = s.substring(0, 100) + '...';
            var mark = (/^\d{15,}$/.test(s)) ? '   <- looks like an id' : '';
            console.log('      ' + f.getName() + ' (' + t + ') = ' + s + mark);
        }
        try { c = c.getSuperclass(); } catch (e) { break; }
    }
}

// ── live-vs-backlog tracking ──────────────────────────────────────────────────
// A0P re-processes EVERY already-unsent item whenever the inbox syncs over MQTT, so
// historical unsends flood in even when idle. We (a) dedupe by item key so the same
// item never prints twice, and (b) treat everything seen in the first BASELINE_MS as
// pre-existing backlog (logged quietly); any NEW unsent item after that = a real,
// happening-now deletion (logged loudly).
var SEEN = {};                 // key -> true
var ATTACH_TIME = Date.now();
var BASELINE_MS = 7000;
var backlogUnsent = 0, backlogNormal = 0;

function itemKey(obj, rc) {
    var u = safeField(obj, rc, V426.fields.user_id);
    var t = safeField(obj, rc, V426.fields.timestamp);
    return u + ':' + t;
}

// ── hook 1: REST path ─────────────────────────────────────────────────────────
// LX/0gL;.parseFromJson(LX/R0r;)LX/9ZA;
// Fires when Instagram fetches thread history from the REST API.
// This is what DirectItemFieldParserFingerprint + Hook 1 in the smali patch targets.

// On MediaTek, building a Java class wrapper (Java.use -> bridge "build") can hit an
// "illegal instruction" if the target class's methods are being JIT-compiled/run on
// other threads at the same moment (a race, not a hard incompatibility — the same
// class builds fine when the app is quiet). Frida surfaces it as a catchable JS Error,
// so we retry the whole install a few times with a delay. Best results: attach to a
// freshly launched app and let hooks install BEFORE generating DM traffic.
var INSTALL_ATTEMPTS = 8;

function installHooks(attempt) {
    // The SIGILL propagates out of Java.perform() itself (it runs the class-building
    // pending VM op inside its own machinery), so the try MUST wrap the perform call,
    // not live inside the callback. Retry the whole thing after a delay.
    try {
        Java.perform(function () { doInstall(); });
    } catch (e) {
        var msg = '' + e;
        if (msg.indexOf('illegal instruction') >= 0 && attempt < INSTALL_ATTEMPTS) {
            console.log('[retry] Java bridge SIGILL during build; retry ' + attempt +
                        '/' + INSTALL_ATTEMPTS + ' in 1.2s (keep the app idle)...');
            setTimeout(function () { installHooks(attempt + 1); }, 1200);
        } else {
            console.log('[!] hook install failed (attempt ' + attempt + '): ' + e);
            console.log('    Relaunch IG fresh and re-attach before opening any DM.');
        }
    }
}

function doInstall() {
    ATTACH_TIME = Date.now();   // start baseline from the successful install, not script load
    console.log('\n[piko] dm-hooks.js initialising for Instagram v426 (attempt active)\n');

    // Hook 1 ─ REST parseFromJson (LX/0gL;.parseFromJson). Fires on thread-history
    // load. DISABLED by default — unstable on MediaTek (see ENABLE_REST_HOOK above).
    if (ENABLE_REST_HOOK) {
        try {
            var ParserClass = Java.use(V426.parserClass);
            ParserClass.parseFromJson.implementation = function (reader) {
                var result = this.parseFromJson(reader);
                // Route through the same detector: parseFromJson is believed to handle
                // realtime UNSEND deltas (v408 RE note), unlike A0P which only does new
                // incoming items. handleItem flags A1Y=true and dumps the item.
                try { if (result) handleItem(result); } catch (e) {}
                return result;
            };
            console.log('[+] Hook 1 installed (' + V426.parserClass + '.parseFromJson) [routed to detector]');
        } catch (e) {
            if (('' + e).indexOf('illegal instruction') >= 0) throw e; // let retry handle it
            console.log('[-] Hook 1 FAILED (' + V426.parserClass + '.parseFromJson): ' + e);
            console.log('    Update V426.parserClass — run discover-classes.js to find new name');
        }
    } else {
        console.log('[i] Hook 1 (REST parseFromJson) disabled - unstable on MediaTek');
    }

    // NOTE: Hook 1b (the X.0gL.A00 per-field dispatcher) was removed. It fires for
    // EVERY JSON field of EVERY item across multiple threads; hooking such a hot
    // function with Frida's Interceptor destabilizes ART on MediaTek (BUS_ADRALN /
    // SIGSEGV on unrelated worker threads). It was only ever a fingerprint-anchor
    // probe and is not needed to locate deletions.

    // Hook 2 ─ MQTT A0P (LX/0gF;.A0P). Fires for EVERY DM item, including a flood of
    // historical items on every inbox sync. We dedupe by item key and split backlog
    // (first BASELINE_MS) from live events, so a real happening-now unsend stands out.
    if (ENABLE_MQTT_HOOK) {
        try {
            var MqttClass = Java.use(V426.mqttClass);
            var overloads = MqttClass.A0P.overloads;
            if (!overloads || overloads.length === 0) throw new Error('A0P has no overloads');
            overloads.forEach(function (ov) {
                ov.implementation = function () {
                    var result = ov.apply(this, arguments);
                    try {
                        if (result !== null) handleItem(result);
                    } catch (e) { /* never let our logging break the app */ }
                    return result;
                };
            });
            console.log('[+] Hook 2 installed (' + V426.mqttClass + '.A0P, ' + overloads.length + ' overload(s))');
        } catch (e) {
            if (('' + e).indexOf('illegal instruction') >= 0) throw e; // let retry handle it
            console.log('[-] Hook 2 FAILED (' + V426.mqttClass + '.A0P): ' + e);
            console.log('    Update V426.mqttClass — grep classes12.dex for "DirectMessage.postprocess"');
        }
    } else {
        console.log('[i] Hook 2 (MQTT A0P) disabled');
    }

    // After the baseline window, announce we're armed for LIVE events and report how
    // much pre-existing backlog we absorbed (so the user knows the noise is over).
    setTimeout(function () {
        console.log('\n[piko] ===== BASELINE DONE (' + (BASELINE_MS/1000) + 's) =====');
        console.log('[piko] Absorbed backlog: ' + backlogUnsent + ' already-unsent, ' +
                    backlogNormal + ' normal items (deduped).');
        console.log('[piko] Now watching LIVE. Send a NEW msg then UNSEND it from the other account.');
        console.log('[piko]   [LIVE MSG]    = a message just arrived');
        console.log('[piko]   *** LIVE UNSEND *** = a message was just deleted  <-- the target event\n');
    }, BASELINE_MS);

    console.log('\n[piko] Ready. Absorbing historical backlog for ' + (BASELINE_MS/1000) + 's (quiet)...');
}

// Decide how to report each A0P item. Key behaviours:
//  - USER_FILTER: bail out early (cheap) for other senders — less work, less freeze.
//  - State-transition dedupe: SEEN[key] stores the last A1Y state. We print on the
//    first sighting AND on a false->true transition (a message that was seen normal and
//    is now unsent = a LIVE deletion). Pure re-syncs of an unchanged item are skipped.
function handleItem(result) {
    var rc;
    try { rc = result.getClass(); } catch (e) { return; }

    // Read user first; filter early to minimize per-item cost on the hot path.
    // readField() uses cached Field handles — critical for stability on MediaTek.
    var user = readField(result, rc, V426.fields.user_id);
    if (USER_FILTER && user !== USER_FILTER) return;

    var unsent = readField(result, rc, V426.fields.hide_in_thread) === 'true';
    var ts     = readField(result, rc, V426.fields.timestamp);
    var key    = user + ':' + ts;
    var prev   = SEEN[key];                 // undefined | false | true
    var live   = (Date.now() - ATTACH_TIME) > BASELINE_MS;

    // Detect the deletion event: an item we already saw as not-unsent now reports unsent.
    var transitionToUnsent = (prev === false && unsent === true);

    if (prev !== undefined && !transitionToUnsent) return;   // unchanged re-sync — skip
    SEEN[key] = unsent;

    // A false->true transition is a real deletion regardless of baseline timing.
    if (transitionToUnsent) {
        reportUnsend('LIVE UNSEND (send->unsend transition)', result, rc, user, ts);
        return;
    }

    if (!live) {                            // first sighting during baseline
        if (unsent) {
            backlogUnsent++;
            // Sample the A0o (X.3jS) structure of the FIRST few historical unsent items.
            // This gives us the deleted-message payload layout in the first seconds —
            // before the MediaTek ~1-min instability — without needing a live unsend.
            if (a0oSamples < A0O_SAMPLE_CAP) {
                a0oSamples++;
                console.log('\n[backlog unsent sample ' + a0oSamples + '/' + A0O_SAMPLE_CAP +
                            '] user=' + user + ' ts=' + ts);
                reportUnsend('backlog sample', result, rc, user, ts);
            }
        } else {
            backlogNormal++;
        }
        return;
    }

    // First sighting, live phase
    if (unsent) {
        reportUnsend('LIVE UNSEND (new unsent item)', result, rc, user, ts);
    } else {
        var text = readField(result, rc, V426.fields.text_content);
        console.log('[LIVE MSG] user=' + user + ' ts=' + ts + ' text=' + text);
    }
}

function reportUnsend(why, result, rc, user, ts) {
    console.log('\n*** LIVE UNSEND / DELETION *** (' + why + ') user=' + user + ' ts=' + ts);
    dumpObject('UNSENT DirectItem', result);
    // Peek inside A0o (the X.3jS action wrapper) for the deleted item's id / text.
    try {
        var a0o = null, c = rc;
        while (c && c.getName() !== 'java.lang.Object') {
            var f = null; try { f = c.getDeclaredField('A0o'); } catch (e) { f = null; }
            if (f) { f.setAccessible(true); a0o = f.get(result); break; }
            c = c.getSuperclass();
        }
        if (a0o !== null) dumpScalars('A0o contents', a0o);
    } catch (e) { console.log('  (A0o inspect failed: ' + e + ')'); }
}

// kick off install (with SIGILL retry)
installHooks(1);
