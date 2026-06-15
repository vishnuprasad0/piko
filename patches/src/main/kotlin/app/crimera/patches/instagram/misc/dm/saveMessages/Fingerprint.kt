/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.saveMessages

import app.morphe.patcher.Fingerprint

/**
 * Targets the method that processes an incoming DirectItem after server deserialization
 * and inserts it into Instagram's local thread store.
 *
 * String "DirectItemDeserializer" appears in the logging call within this method.
 * If this fingerprint fails, use ObjectBrowser on a received DirectItem object,
 * find the class name, then search smali for log calls containing that class name.
 */
internal object DirectMessageReceiveFingerprint : Fingerprint(
    strings = listOf("DirectItemDeserializer"),
    returnType = "V",
)

/**
 * Targets the method that handles a server-signalled message unsend/delete.
 * Instagram uses "unsend" as the action key when a DM is deleted by the sender.
 *
 * String "DirectUnsendItemUseCase" appears in the logging call within the unsend handler.
 * If this fingerprint fails, search smali for "unsend" or "item_removed" string constants.
 */
internal object DirectMessageUnsendFingerprint : Fingerprint(
    strings = listOf("DirectUnsendItemUseCase"),
    returnType = "V",
)
