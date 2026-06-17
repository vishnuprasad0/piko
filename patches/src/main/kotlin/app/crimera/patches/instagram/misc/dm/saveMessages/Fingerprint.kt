/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.saveMessages

import app.morphe.patcher.Fingerprint

/**
 * Targets Instagram's DirectThreadItem JSON parser — static analysis confirmed
 * method: LX/5jI;->A01(LX/5Oo;Lcom/instagram/model/direct/DirectThreadKey;Z)LX/5jI;
 * (v408 reference APK; equivalent class in v426 is LX/9ZA with same method structure).
 *
 * Anchor strings (v408 reference):
 *   "item_id", "user_id", "item_type" — inline JSON key comparisons in the parser dispatch.
 *   NOTE: "hide_in_thread" is NOT a reliable anchor — in v426 it is absent as an
 *   inline const-string (hash-table dispatch or different dex distribution).
 *   These three strings co-occur in 12 dex files but Morphe selects the best candidate;
 *   the returnType filter eliminates the void-return serialiser LX/8lD;->A00.
 *
 * TODO (needs v426 dex grep): find a UNIQUE string constant that is:
 *   - present as const-string in the same method as "item_id" in v426
 *   - absent from all other classes → replace "user_id" with that unique anchor
 *
 * hideInThread field on the returned domain object:
 *   v408 (LX/5jI;):  field A2V:Z
 *   v426 (LX/9ZA;):  field A1Y:Z   ← from prior RE session, needs ObjectBrowser confirm
 *
 * Realtime unsend flow (confirmed from smali):
 *   iris MQTT delta → 5jI.A01 (JSON parse, sets hideInThread) → Oq2.A00 merge
 *   → L9t (replace_message wrapper) → Qjw.GFS render dispatch
 *
 * The parse hook (Hook 1) therefore fires for BOTH initial loads AND realtime unsends.
 * No separate removal hook is needed for the regular-DM path.
 */
internal object DirectMessageItemParseFingerprint : Fingerprint(
    strings = listOf("item_id", "user_id", "item_type"),
    returnType = "Ljava/lang/Object;",
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
