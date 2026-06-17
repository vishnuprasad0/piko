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
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getFreeRegisterProvider
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
            // DirectItemFieldParserFingerprint matches LX/0gL;.A00 (the per-field JSON
            // dispatch helper) to locate the parser class. We then hook parseFromJson in
            // that same class — it returns the fully-populated DirectItem (LX/9ZA;).
            // This is necessary because parseFromJson itself contains no string constants
            // (it just delegates to unsafeParseFromJson), while A00 uniquely identifies
            // the class via "item_id" + "hide_in_thread" only present together in classes12.
            DirectItemFieldParserFingerprint.classDef.methods.first { it.name == "parseFromJson" }.apply {
                val returnObjInstruction = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val returnObjIndex = returnObjInstruction.location.index
                val itemRegister = returnObjInstruction.registersUsed[0]

                addInstructions(
                    returnObjIndex,
                    """
                    invoke-static {v$itemRegister}, $HOOK_CLASS->onMessageReceived(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // --- Hook 2: Capture messages delivered via MQTT/MSys real-time sync ---
            // parseFromJson (Hook 1) only fires for REST/JSON loads (thread history).
            // Real-time messages arrive via MQTT Thrift encoding and never touch parseFromJson.
            // A0P is the universal post-processing step called after every MQTT DirectItem is
            // assembled from a sync delta; hooking its last success return covers that path.
            // LX/0gF; extends LX/9ZA; (no new fields), so the same onMessageReceived handler
            // works for both — it walks the superclass chain to find the LX/9ZA; fields.
            DirectItemPostprocessFingerprint.method.apply {
                val returnObjInstruction = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val returnObjIndex = returnObjInstruction.location.index
                val itemRegister = returnObjInstruction.registersUsed[0]

                // A0P has many local registers; the success return uses v17 (= p0 = this).
                // invoke-static only supports v0-v15 (4-bit register field), so move first.
                val reg = getFreeRegisterProvider(index = returnObjIndex, numberOfFreeRegistersNeeded = 1).getFreeRegister()

                addInstructions(
                    returnObjIndex,
                    """
                    move-object/from16 v$reg, v$itemRegister
                    invoke-static {v$reg}, $HOOK_CLASS->onMessageReceived(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            // --- Hook 3: Inject "View deleted messages" button into compose bar ---
            // onTextChanged fires every time the user types in the DM input.
            // Our hook checks if the button is already present and, if not, adds it.
            // p0 = the TextWatcher instance (used to locate the EditText via reflection).
            DirectComposeBarTextWatcherFingerprint.method.apply {
                val reg = getFreeRegisterProvider(index = 0, numberOfFreeRegistersNeeded = 1).getFreeRegister()

                addInstructions(
                    0,
                    """
                    move-object/from16 v$reg, p0
                    invoke-static {v$reg}, $HOOK_CLASS->addDeletedMessagesButton(Ljava/lang/Object;)V
                    """.trimIndent(),
                )
            }

            enableSettings("saveDeletedMessages")
        }
    }
