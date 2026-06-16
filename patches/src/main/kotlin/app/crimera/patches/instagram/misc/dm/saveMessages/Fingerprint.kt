/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.saveMessages

import app.morphe.patcher.Fingerprint

/**
 * Targets Instagram's DirectThreadItem JSON parser.
 * "item_id", "user_id", "item_type" are literal field-name constants in the
 * JSONObject.getString()/optString() calls inside this method — same approach
 * as EphemeralMediaJsonParserFingerprint uses "url_expire_at_secs" etc.
 */
internal object DirectMessageItemParseFingerprint : Fingerprint(
    strings = listOf("item_id", "user_id", "item_type"),
    returnType = "Ljava/lang/Object;",
)

// NOTE (v426 RE): real-time unsend is NOT a separate (threadId, itemId) callback.
// On v426 the const-string "item_removed" does not exist anywhere in the APK.
// An incoming unsend is represented by the DirectItem model carrying
// hide_in_thread = true (model class LX/9ZA, field A1Y:Z; serialized as the
// JSON key "hide_in_thread", sibling flag "is_deleted_for_self").
// Detecting deletion therefore belongs in the parse hook (Hook 1), but mapping
// the obfuscated boolean field on the *captured* parse object requires runtime
// ObjectBrowser inspection on a live patched build (see docs/re-notes). Until
// that field is confirmed, the dedicated removal hook is omitted so the patch
// applies cleanly. The extension's onMessageDeleted(...) is retained for when
// the runtime field/handler is identified.

/**
 * Targets the TextWatcher attached to the DM compose bar EditText.
 * Same class as DisableTypingStatusPatch's OnTextChangedFingerprint — we
 * duplicate the fingerprint here so we can access classDef independently
 * and inject the compose-bar button setup without touching the typing patch.
 */
internal object DirectComposeBarTextWatcherFingerprint : Fingerprint(
    name = "onTextChanged",
    parameters = listOf("Ljava/lang/CharSequence;", "I", "I", "I"),
    returnType = "V",
)
