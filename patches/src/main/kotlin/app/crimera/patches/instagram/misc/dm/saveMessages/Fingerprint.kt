/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.saveMessages

import app.morphe.patcher.Fingerprint

/**
 * Targets LX/0gL;.A00(LX/R0r;LX/9ZA;Ljava/lang/String;)Z in v426 classes12.dex.
 *
 * This is the per-field JSON dispatch helper: called once per JSON key during
 * DirectItem deserialization. It compares the current key against known field
 * names and writes matching values into the DirectItem (LX/9ZA;).
 *
 * The two anchor strings are uniquely co-located ONLY in this method across all
 * 19 dex files — "hide_in_thread" appears only in classes12.dex with "item_id".
 * returnType = "Z" (boolean) distinguishes it from the void serializer
 * LX/0gG;.A00(LX/R1V;LX/0gF;Z)V which also contains both strings.
 *
 * This fingerprint is NOT hooked directly. It is used only to locate the
 * containing class LX/0gL;, from which parseFromJson is retrieved and hooked.
 *
 * v426 field mapping (confirmed from dexdump classes12.dex):
 *   item_id        → LX/9ZA;.A13:Ljava/lang/String;
 *   hide_in_thread → LX/9ZA;.A1Y:Z
 *   user_id        → LX/9ZA;.A1M:Ljava/lang/String;
 *   timestamp      → LX/9ZA;.A1J:Ljava/lang/String;  (microseconds string)
 *   text           → LX/9ZA;.A1I:Ljava/lang/String;
 *   item_type      → LX/9ZA;.A0Y:LX/8ot; (enum — use toString())
 *   thread_key     → LX/9ZA;.A0W:Lcom/instagram/model/direct/DirectThreadKey;
 *   DirectThreadKey.threadId → .A00:Ljava/lang/String; (same as v408)
 *
 * v408 field mapping (for fallback):
 *   DirectItem: LX/5jI;, hideInThread: A2V:Z, item_id: getter A0l(), threadKey: A16/A18
 */
internal object DirectItemFieldParserFingerprint : Fingerprint(
    strings = listOf("item_id", "hide_in_thread"),
    returnType = "Z",
)

/**
 * Targets LX/0gF;.A0P in v426 classes12.dex.
 *
 * A0P is the post-processing step called after EVERY DirectItem is assembled
 * from an MSys/MQTT sync delta. It is NOT called by the REST/JSON parseFromJson
 * path — those are two separate code paths. Hooking A0P here covers real-time
 * MQTT delivery (which parseFromJson never sees).
 *
 * LX/0gF; extends LX/9ZA; (DirectItem) with no additional instance fields;
 * all data fields (A13=item_id, A1Y=hide_in_thread, etc.) live on LX/9ZA;.
 * SavedMessagesHook.onMessageReceived walks the superclass chain to find them.
 *
 * The two anchor strings are uniquely co-located in A0P only in classes12.dex.
 * returnType not specified to avoid hardcoding the obfuscated class name.
 */
internal object DirectItemPostprocessFingerprint : Fingerprint(
    strings = listOf("DirectMessage.postprocess.%s", "Encountered DirectMessage with null type"),
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
