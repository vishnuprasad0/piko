/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dm.saveMessages

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.INTEGRATIONS_PACKAGE
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private const val HOOK_DESCRIPTOR = "$INTEGRATIONS_PACKAGE/patches/dm/SavedMessagesHook;"

@Suppress("unused")
val saveDeletedMessagesPatch =
    bytecodePatch(
        name = "Save deleted messages",
        description = "Intercepts incoming DMs at the server-receive point and stores them locally. Marks messages as deleted when the sender unsends them.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // --- Hook 1: Capture incoming messages ---
            // Targets the method that stores a received DirectItem in the thread.
            // Assumed signature: void method(String threadId, Object directItem, ...)
            // p0 = this, p1 = threadId (String), p2 = DirectItem object.
            // If the fingerprint matches a different signature, inspect the smali and
            // adjust the p-register indices below accordingly.
            DirectMessageReceiveFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                    invoke-static {p2, p1}, $HOOK_DESCRIPTOR->onMessageReceived(Ljava/lang/Object;Ljava/lang/String;)V
                    """.trimIndent(),
                )
            }

            // --- Hook 2: Detect message deletion (unsend) ---
            // Targets the method that handles an item_removed / unsend MQTT event.
            // Assumed signature: void method(String threadId, String itemId, ...)
            // p0 = this, p1 = threadId (String), p2 = itemId (String).
            DirectMessageUnsendFingerprint.method.apply {
                addInstructions(
                    0,
                    """
                    invoke-static {p2, p1}, $HOOK_DESCRIPTOR->onMessageDeleted(Ljava/lang/String;Ljava/lang/String;)V
                    """.trimIndent(),
                )
            }

            enableSettings("saveDeletedMessages")
        }
    }
