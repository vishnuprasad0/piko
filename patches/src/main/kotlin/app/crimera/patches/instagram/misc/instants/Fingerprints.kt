/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.instants

import app.morphe.patcher.Fingerprint

/** Class-level fingerprint for QuickSnapCameraViewModel — resolves the class so its instance
 *  methods can be iterated in the execute block to stash the live VM. Unobfuscated in stock v435. */
internal object QuickSnapCameraViewModelClassFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel;",
)

/** Anchors InstantsHook.names(), whose const-strings are sentinel placeholders for obfuscated
 *  field/method/class names. instantsGalleryPatch rewrites them at patch time (§11) by matching
 *  each sentinel's text, so the array's order is not a contract. If resolution fails the feature is
 *  not installed at all — the sentinels are never left in place as a usable fallback. */
internal object InstantsNamesFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/instagram/patches/instants/InstantsHook;",
    name = "names",
)

internal object OnCapturedBitmapConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel\$onCaptured\$4;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/graphics/Bitmap;",
        "Lcom/instagram/quicksnap/camera/domain/QuickSnapCameraViewModel;",
        "L",
        "Ljava/lang/String;",
        "L",
        "J",
        "J",
    ),
)
