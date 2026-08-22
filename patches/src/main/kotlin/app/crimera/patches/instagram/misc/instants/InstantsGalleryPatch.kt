/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.instants

import app.crimera.patches.instagram.misc.extension.hooks.instagramInitHook
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.INSTANTS_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.crimera.utils.changeStringAt
import app.crimera.utils.classNameToExtension
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val INSTANTS_HOOK_DESCRIPTOR = "$INSTANTS_DESCRIPTOR/InstantsHook;"

/** Every obfuscated name InstantsHook's auto-post path reflects on, recovered from the target dex. */
private data class InstantsNames(
    val vmUuidField: String,
    val vmStateFlowField: String,
    val stateIlkField: String,
    val vmCtrlField: String,
    val ctrlLockMethod: String,
    val scopeClass: String,
    val scopeMethod: String,
    val dispatchClass: String,
    val dispatchMethod: String,
    val vmPreStep1: String,
    val vmPreStep2: String,
)

/**
 * Rewrites the InstantsHook.names() placeholder whose *value* is [placeholder].
 *
 * Matches on sentinel text rather than an index so the array's declaration order isn't a contract.
 * A missing or duplicated sentinel is a names()/resolver desync — our own bug, deterministic across
 * builds — so it throws rather than degrading.
 */
context(patchContext: BytecodePatchContext)
private fun Fingerprint.replacePlaceholder(
    placeholder: String,
    value: String,
) {
    val hits =
        method.instructions
            .filter { it.opcode == Opcode.CONST_STRING }
            .withIndex()
            .filter { (_, insn) -> (insn as ReferenceInstruction).reference.toString() == placeholder }

    if (hits.size != 1) {
        throw PatchException(
            "InstantsHook.names(): expected exactly one \"$placeholder\" placeholder, found ${hits.size}. " +
                "The extension's names() and instantsGalleryPatch are out of sync.",
        )
    }

    changeStringAt(hits.single().index, value)
}

/**
 * "Instants gallery post": overlays an "Add from gallery" button on Instagram's Instants (quicksnap)
 * camera. It opens a picker, then a preview whose "Post to Instant" button publishes the image
 * directly on the live QuickSnapCameraViewModel — no shutter press (see InstantsHook).
 *
 * Anchors: the unobfuscated QuickSnapCameraViewModel class and its onCaptured$4 nested class
 * (verified via baksmali against stock v435; see piko-re/re-notes/instants-quicksnap-v435.md).
 *
 * Installs all-or-nothing: names are resolved first, and unless every one is recovered no hook,
 * button or toggle is installed. An Instagram-side miss disables only this feature, never the
 * session. There is no last-known-good name fallback — obfuscated names rotate on every R8 run. The
 * only throwing case is our own names()/resolver desync (see replacePlaceholder).
 */
@Suppress("unused")
val instantsGalleryPatch =
    bytecodePatch(
        name = "Instants gallery post",
        description = "Adds ability to post a gallery image to an Instagram Instant.",
    ) {
        dependsOn(settingsPatch, instantsResourcePatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Step 1: recover every obfuscated name up front — resolution gates the whole feature.
            // Each name is recovered by shape (field types, iget→consumer dataflow, method
            // signatures) anchored on the unobfuscated VM, so a rename is a non-event; only a real
            // restructure of the publish flow breaks it. Any miss installs nothing and returns.
            val names =
                runCatching { resolveNames() }.getOrElse { error ->
                    println(
                        "[piko] Instants gallery post: DISABLED for this build — could not resolve " +
                            "the QuickSnap publish flow: ${error.message}",
                    )
                    return@execute
                }

            // Steps 2-4: install hooks, bake the names, then expose the toggle. Order matters — the
            // marker is written last and enableSettings runs only after it, so any failure leaves
            // the marker unset (runtime refuses to auto-post) and the toggle never appears.
            runCatching {
                OnCapturedBitmapConstructorFingerprint.method.addInstructions(
                    0,
                    """
                    invoke-static {p1, p2, p3}, $INSTANTS_HOOK_DESCRIPTOR->onCapturedBitmap(Landroid/content/Context;Landroid/graphics/Bitmap;Ljava/lang/Object;)Landroid/graphics/Bitmap;
                    move-result-object p2
                    """.trimIndent(),
                )

                // Bootstrap the Activity lifecycle tracker at app startup so the overlay button can
                // appear on the camera even before settings are opened. Injected at the return of
                // InstagramAppShell.onCreate (resolved by sharedExtensionPatch via settingsPatch).
                instagramInitHook.fingerprint.method.apply {
                    // Insert right after invoke-super: the register holding the context there is
                    // reused later in onCreate, so reading it at the return fails verification.
                    val invokeSuperIndex = indexOfFirstInstruction(Opcode.INVOKE_SUPER)
                    val contextRegister = getInstruction(invokeSuperIndex).registersUsed[0]
                    addInstruction(
                        invokeSuperIndex + 1,
                        """
                        invoke-static {v$contextRegister}, $INSTANTS_HOOK_DESCRIPTOR->initActivityTracker(Landroid/content/Context;)V
                        """.trimIndent(),
                    )
                }

                // Stash the live QuickSnapCameraViewModel for the auto-post path. Rather than guess
                // which lifecycle callback fires on camera-open, inject an idempotent weak-ref store
                // into every non-static instance method (p0 = this). Anchored on the unobfuscated
                // class only (§11). The class must be taken as mutable — writes to the methods
                // of a fingerprint's classDef land on a copy and are dropped.
                val stashed =
                    mutableClassDefBy(QuickSnapCameraViewModelClassFingerprint.classDef)
                        .methods.filter { m ->
                            !AccessFlags.STATIC.isSet(m.accessFlags) &&
                                m.name != "<init>" &&
                                m.implementation != null
                        }.count { m ->
                            runCatching {
                                m.addInstruction(
                                    0,
                                    "invoke-static/range {p0 .. p0}, $INSTANTS_HOOK_DESCRIPTOR->noteLiveViewModel(Ljava/lang/Object;)V",
                                )
                            }.isSuccess
                        }

                if (stashed == 0) {
                    throw PatchException(
                        "QuickSnapCameraViewModel: could not stash the live ViewModel in any " +
                            "instance method — auto-post would have no ViewModel to publish on.",
                    )
                }

                InstantsNamesFingerprint.apply {
                    replacePlaceholder("piko.instants.vmUuidField", names.vmUuidField)
                    replacePlaceholder("piko.instants.vmStateFlowField", names.vmStateFlowField)
                    replacePlaceholder("piko.instants.stateIlkField", names.stateIlkField)
                    replacePlaceholder("piko.instants.vmCtrlField", names.vmCtrlField)
                    replacePlaceholder("piko.instants.ctrlLockMethod", names.ctrlLockMethod)
                    replacePlaceholder("piko.instants.scopeClass", names.scopeClass)
                    replacePlaceholder("piko.instants.scopeMethod", names.scopeMethod)
                    replacePlaceholder("piko.instants.dispatchClass", names.dispatchClass)
                    replacePlaceholder("piko.instants.dispatchMethod", names.dispatchMethod)
                    replacePlaceholder("piko.instants.vmPreStep1", names.vmPreStep1)
                    replacePlaceholder("piko.instants.vmPreStep2", names.vmPreStep2)

                    // Commit point — must stay last.
                    replacePlaceholder("piko.instants.marker", "1")
                }

                enableSettings("instantsGalleryPost")
            }.onFailure { error ->
                println(
                    "[piko] Instants gallery post: DISABLED for this build — hook installation " +
                        "failed: ${error.message}",
                )
            }
        }
    }

/**
 * Recovers every obfuscated name the auto-post path needs, from the target dex only. Anchored on the
 * unobfuscated onCaptured$4 type — its sole construction site is the publish method a shutter press
 * runs — plus the QuickSnapCameraViewModel classdef.
 *
 * Field lookups match within a small window and verify register dataflow (the consuming call must
 * read the register the iget wrote), so R8 may reorder between the two instructions without breaking
 * us while an unrelated call can't be mistaken for the one we want. Throws on any miss.
 */
context(patchContext: BytecodePatchContext)
private fun resolveNames(): InstantsNames {
    val vmType = QuickSnapCameraViewModelClassFingerprint.classDef.type
    val ocType = OnCapturedBitmapConstructorFingerprint.method.definingClass
    // IlK (capture params) is onCaptured$4 constructor parameter index 3 — read from the
    // fingerprint rather than named, so its obfuscated name is never written down.
    val ilkType = OnCapturedBitmapConstructorFingerprint.method.parameterTypes[3].toString()

    fun buildsOc(m: Method) =
        m.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE &&
                (it as ReferenceInstruction).reference.toString() == ocType
        } == true

    // The method that constructs onCaptured$4 is the publish method we mirror. Exclude onCaptured$4
    // itself: as a Kotlin suspend lambda its generated create() constructs its own type, and would
    // otherwise be matched instead of the real publisher.
    val publisher =
        patchContext.mutableClassDefBy { cd ->
            cd.type != ocType && cd.methods.any { m -> buildsOc(m) }
        }
    val insns = publisher.methods.first { buildsOc(it) }.instructions.toList()

    fun fieldOf(i: Int) = (insns[i] as ReferenceInstruction).reference as FieldReference

    fun methodOf(i: Int) = (insns[i] as ReferenceInstruction).reference as MethodReference

    fun isIget(i: Int) = insns[i].opcode.name.startsWith("iget-object", ignoreCase = true)

    fun destReg(i: Int) = (insns[i] as OneRegisterInstruction).registerA

    // The value an iget wrote is normally consumed within a couple of instructions; a small window
    // absorbs codegen jitter without reaching far enough to collide with unrelated code.
    val window = 6

    /** First invoke within [window] after [i] whose receiver register is [reg] and which matches [pred]. */
    fun invokeOn(
        i: Int,
        reg: Int,
        pred: (MethodReference) -> Boolean,
    ): MethodReference? {
        for (j in i + 1..minOf(i + window, insns.lastIndex)) {
            val insn = insns[j]
            if (insn.opcode != Opcode.INVOKE_VIRTUAL && insn.opcode != Opcode.INVOKE_INTERFACE) continue
            if (insn.registersUsed.firstOrNull() != reg) continue
            val mr = (insn as ReferenceInstruction).reference as? MethodReference ?: continue
            if (pred(mr)) return mr
        }
        return null
    }

    val ncIndex =
        insns.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE &&
                (it as ReferenceInstruction).reference.toString() == ocType
        }

    // State flow: a VM field whose value has getValue() called on it.
    // v435: iget-object v6, v2, VM->A0F:LX/EuJ; / invoke-interface {v6}, LX/EuJ;->getValue().
    val vmStateFlowField =
        insns.indices.firstNotNullOfOrNull { i ->
            if (!isIget(i) || fieldOf(i).definingClass != vmType) return@firstNotNullOfOrNull null
            invokeOn(i, destReg(i)) { it.name == "getValue" && it.parameterTypes.isEmpty() }
                ?: return@firstNotNullOfOrNull null
            fieldOf(i).name
        } ?: throw PatchException("no VM field feeding a getValue() call (capture state flow).")

    // Capture-state field holding IlK: the iget whose field type is IlK.
    val stateIlkField =
        insns.indices.firstOrNull { isIget(it) && fieldOf(it).type == ilkType }
            ?.let { fieldOf(it).name }
            ?: throw PatchException("no field of type $ilkType read in the publish method.")

    // Capture controller: a VM field whose value has a no-arg void method called on it.
    val ctrlPair =
        insns.indices.firstNotNullOfOrNull { i ->
            if (!isIget(i) || fieldOf(i).definingClass != vmType) return@firstNotNullOfOrNull null
            val fr = fieldOf(i)
            val mr =
                invokeOn(i, destReg(i)) {
                    it.definingClass == fr.type && it.parameterTypes.isEmpty() && it.returnType == "V"
                } ?: return@firstNotNullOfOrNull null
            fr.name to mr.name
        } ?: throw PatchException("no VM field with a no-arg void call (capture controller).")

    // Scope helper: the last 1-arg invoke-static returning an object before the onCaptured$4
    // construction. Same arity/return the runtime reflection looks for, so what resolves is callable.
    val scopeRef =
        (0 until ncIndex).lastOrNull {
            insns[it].opcode == Opcode.INVOKE_STATIC &&
                methodOf(it).parameterTypes.size == 1 &&
                methodOf(it).returnType.startsWith("L")
        }?.let { methodOf(it) }
            ?: throw PatchException("no coroutine-scope helper before the onCaptured\$4 construction.")

    // Dispatch: the first 2-arg invoke-static after the onCaptured$4 <init>.
    // The <init> takes 8 args, so R8 emits invoke-direct/range, not plain invoke-direct.
    val initIdx =
        (ncIndex until insns.size).firstOrNull {
            (insns[it].opcode == Opcode.INVOKE_DIRECT || insns[it].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                (insns[it] as ReferenceInstruction).reference.let { r ->
                    r is MethodReference && r.definingClass == ocType && r.name == "<init>"
                }
        } ?: throw PatchException("onCaptured\$4 is constructed but never initialised.")

    val dispatchRef =
        (initIdx + 1 until insns.size).firstOrNull {
            insns[it].opcode == Opcode.INVOKE_STATIC && methodOf(it).parameterTypes.size == 2
        }?.let { methodOf(it) }
            ?: throw PatchException("no coroutine dispatch after the onCaptured\$4 construction.")

    // Static pre-steps, distinguished by signature: (VM)V and (VM, String)V. Several statics share
    // the (VM)V shape, so take the first one called in the publish method — that's the shutter's
    // own call order, which is the semantics we reproduce.
    fun staticVmMethod(vararg params: String) =
        insns.indices.firstNotNullOfOrNull { i ->
            if (insns[i].opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
            val mr = (insns[i] as ReferenceInstruction).reference as? MethodReference
                ?: return@firstNotNullOfOrNull null
            if (mr.definingClass == vmType && mr.returnType == "V" &&
                mr.parameterTypes.map { p -> p.toString() } == params.toList()
            ) {
                mr.name
            } else {
                null
            }
        } ?: throw PatchException("no VM static pre-step with signature (${params.joinToString()})V.")

    // Capture-session UUID: the only volatile String instance field on the VM.
    val vmUuidField =
        QuickSnapCameraViewModelClassFingerprint.classDef.fields.filter { f ->
            !AccessFlags.STATIC.isSet(f.accessFlags) &&
                AccessFlags.VOLATILE.isSet(f.accessFlags) &&
                f.type == "Ljava/lang/String;"
        }.let { candidates ->
            // Ambiguity would silently seed the wrong field (a malformed publish, not a crash),
            // so refuse instead of guessing.
            when (candidates.size) {
                1 -> candidates.single().name
                else -> throw PatchException(
                    "expected exactly one volatile String field on QuickSnapCameraViewModel, " +
                        "found ${candidates.size} — cannot identify the capture-session UUID.",
                )
            }
        }

    return InstantsNames(
        vmUuidField = vmUuidField,
        vmStateFlowField = vmStateFlowField,
        stateIlkField = stateIlkField,
        vmCtrlField = ctrlPair.first,
        ctrlLockMethod = ctrlPair.second,
        scopeClass = classNameToExtension(scopeRef.definingClass),
        scopeMethod = scopeRef.name,
        dispatchClass = classNameToExtension(dispatchRef.definingClass),
        dispatchMethod = dispatchRef.name,
        vmPreStep1 = staticVmMethod(vmType),
        vmPreStep2 = staticVmMethod(vmType, "Ljava/lang/String;"),
    )
}
