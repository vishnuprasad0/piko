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

/**
 * Targets the MQTT real-time event handler for item removal.
 * "item_removed" is the action-type string sent by Instagram's server when
 * a sender unsends (deletes) a message. Appears as a const-string in the
 * switch/if block that dispatches MQTT direct thread events.
 */
internal object DirectMessageItemRemovedFingerprint : Fingerprint(
    strings = listOf("item_removed"),
    returnType = "V",
)

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
