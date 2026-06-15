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
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findFreeRegister
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private const val HOOK_CLASS = "$INTEGRATIONS_PACKAGE/patches/dm/SavedMessagesHook;"

@Suppress("unused")
val saveDeletedMessagesPatch =
    bytecodePatch(
        name = "Save deleted messages",
        description = "Captures incoming DMs locally as they arrive from the server and marks them when the sender deletes them.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {

            // --- Hook 1: Capture each message as it arrives from server ---
            // Hooks the DirectThreadItem JSON parser just before it returns the
            // parsed object. We capture the object here for storage.
            DirectMessageItemParseFingerprint.method.apply {
                val returnObjIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                val itemRegister = getInstruction(returnObjIndex).registersUsed[0]

                addInstructions(
                    returnObjIndex,
                    """
                    invoke-static {v$itemRegister}, $HOOK_CLASS->onMessageReceived(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // --- Hook 2: Detect real-time message deletion (unsend) ---
            // Hooks the MQTT event handler that fires when "item_removed" action
            // arrives. Passes the first two String parameters (threadId, itemId).
            DirectMessageItemRemovedFingerprint.method.apply {
                val freeReg = findFreeRegister(0)

                addInstructions(
                    0,
                    """
                    move-object/from16 v$freeReg, p1
                    move-object/from16 v${freeReg + 1}, p2
                    invoke-static {v$freeReg, v${freeReg + 1}}, $HOOK_CLASS->onMessageDeleted(Ljava/lang/String;Ljava/lang/String;)V
                    """.trimIndent(),
                )
            }

            // --- Hook 3: Inject "View deleted messages" button into compose bar ---
            // Hooks onTextChanged of the DM compose bar TextWatcher. On the first
            // call, our extension checks whether the button is already present in the
            // EditText's parent and, if not, adds it. p0 = the TextWatcher instance,
            // from which we can reach the EditText via reflection.
            DirectComposeBarTextWatcherFingerprint.method.apply {
                val freeReg = findFreeRegister(0)

                addInstructions(
                    0,
                    """
                    move-object/from16 v$freeReg, p0
                    invoke-static {v$freeReg}, $HOOK_CLASS->addDeletedMessagesButton(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            enableSettings("saveDeletedMessages")
        }
    }
