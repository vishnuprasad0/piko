/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.instants

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/** Registers the gallery picker and preview Activities for "Instants gallery post". Kept out of the
 *  shared settings resource patch so they only ship when the feature is applied. The preview needs a
 *  plain platform theme — the app's Theme.Instagram.Splash breaks plain widget construction. */
val instantsResourcePatch =
    resourcePatch {
        finalize {
            document("AndroidManifest.xml").use { document ->
                val application = document.getElementsByTagName("application").item(0) as Element

                var activity = document.createElement("activity")
                activity.setAttribute("android:name", "app.morphe.extension.instagram.patches.instants.InstantsGalleryPickerActivity")
                activity.setAttribute("android:exported", "false")
                application.appendChild(activity)

                activity = document.createElement("activity")
                activity.setAttribute("android:name", "app.morphe.extension.instagram.patches.instants.InstantsPreviewActivity")
                activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
                activity.setAttribute("android:exported", "false")
                application.appendChild(activity)
            }
        }
    }
