/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.comment

import app.crimera.patches.instagram.utils.Constants.COMMENT_BUTTON_EXTENSION_CLASS
import app.morphe.patcher.Fingerprint

internal const val HANDLE_COMMENT_BUTTON_EXTENSION_CLASS = "${COMMENT_BUTTON_EXTENSION_CLASS}/HandleCommentButton;"

// The impression string is unique to the comment-button builder, so it carries the match on its own.
// The declared return type is deliberately loose: it is List up to v439 and ArrayList on v441.
internal object AddCommentButtonFingerprint : Fingerprint(
    strings = listOf("instagram_share_comment_to_story_entrypoint_impression"),
    custom = { methodDef, _ ->
        methodDef.returnType == "Ljava/util/List;" || methodDef.returnType == "Ljava/util/ArrayList;"
    },
)
