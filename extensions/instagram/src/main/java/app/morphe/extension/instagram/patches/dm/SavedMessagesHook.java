/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.lang.reflect.Field;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.db.PikoMessageDb;
import app.morphe.extension.instagram.entity.Entity;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class SavedMessagesHook {

    private static final String BUTTON_TAG = "piko_deleted_msgs_btn";

    // -------------------------------------------------------------------------
    // Hook 1: called just before DirectThreadItem parser returns the parsed object.
    // We extract fields via reflection because Instagram's model is ProGuard-obfuscated.
    // Use ObjectBrowser on the item object to discover exact field names for each version.
    // -------------------------------------------------------------------------
    public static void onMessageReceived(Object item) {
        try {
            if (!Pref.saveDeletedMessages()) return;
            if (item == null) return;

            Entity msg = new Entity(item);

            String messageId  = reflectString(item, "item_id",   "A00");
            String threadId   = reflectString(item, "thread_id", "A05");
            String senderId   = null;
            String senderUser = null;
            String content    = null;
            String type       = reflectString(item, "item_type", "A01");
            long   timestamp  = System.currentTimeMillis();

            // Try to get sender info from nested object
            try {
                Object senderObj = findFieldByType(item, "user");
                if (senderObj != null) {
                    senderId   = reflectString(senderObj, "pk",       "A00");
                    senderUser = reflectString(senderObj, "username", "A01");
                }
            } catch (Exception ignored) {}

            // Try to get timestamp
            try {
                Object ts = reflectRaw(item, "timestamp", "A03");
                if (ts instanceof Long)   timestamp = (Long) ts;
                if (ts instanceof Number) timestamp = ((Number) ts).longValue();
            } catch (Exception ignored) {}

            // Try to get text content from nested text entity
            try {
                Object textObj = findFieldByNameHint(item, "text");
                if (textObj instanceof String) {
                    content = (String) textObj;
                } else if (textObj != null) {
                    content = reflectString(textObj, "text", "A00");
                }
            } catch (Exception ignored) {}

            if (messageId == null) return;

            PikoMessageDb.getInstance(PikoUtils.getContext())
                .insertOrIgnore(messageId, threadId, senderId, senderUser, content, type, timestamp);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageReceived: " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 2: called when MQTT "item_removed" event arrives.
    // p1 = threadId, p2 = itemId (Instagram's canonical order in the unsend handler).
    // -------------------------------------------------------------------------
    public static void onMessageDeleted(String threadId, String itemId) {
        try {
            if (!Pref.saveDeletedMessages()) return;
            if (itemId == null) return;

            PikoMessageDb.getInstance(PikoUtils.getContext()).markDeleted(itemId);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageDeleted: " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 3: called from onTextChanged of the DM compose bar TextWatcher.
    // Adds a small icon button to the left side of the compose bar on first call.
    // -------------------------------------------------------------------------
    public static void addDeletedMessagesButton(Object textWatcher) {
        try {
            if (!Pref.saveDeletedMessages()) return;

            // Locate the EditText that this TextWatcher is watching
            EditText editText = findEditText(textWatcher);
            if (editText == null) return;

            ViewGroup parent = (ViewGroup) editText.getParent();
            if (parent == null) return;

            // Only add once per compose bar instance
            if (parent.findViewWithTag(BUTTON_TAG) != null) return;

            // Try to recover thread_id from the TextWatcher's class fields
            String threadId = findThreadId(textWatcher);

            Context ctx = editText.getContext();

            ImageButton btn = new ImageButton(ctx);
            btn.setTag(BUTTON_TAG);
            btn.setContentDescription("View deleted messages");
            btn.setBackgroundColor(Color.TRANSPARENT);

            // Reuse a common inbox-style icon available in Instagram's resources
            try {
                int resId = ctx.getResources().getIdentifier(
                    "direct_inbox_icon", "drawable", ctx.getPackageName());
                if (resId == 0) resId = ctx.getResources().getIdentifier(
                    "direct_message_icon", "drawable", ctx.getPackageName());
                if (resId != 0) {
                    btn.setImageResource(resId);
                    btn.setColorFilter(0xFFAAAAAA, PorterDuff.Mode.SRC_IN);
                }
            } catch (Exception ignored) {}

            int size = (int) (40 * ctx.getResources().getDisplayMetrics().density);
            int margin = (int) (4 * ctx.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.leftMargin = margin;
            params.rightMargin = margin;

            final String finalThreadId = threadId;
            btn.setOnClickListener(v -> {
                Intent intent = new Intent(ctx, DeletedMessagesActivity.class);
                if (finalThreadId != null) intent.putExtra("thread_id", finalThreadId);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            });

            // Insert at position 0 so it appears to the left of the compose input
            parent.addView(btn, 0, params);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.addDeletedMessagesButton: " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    private static EditText findEditText(Object obj) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object val = f.get(obj);
                if (val instanceof EditText) return (EditText) val;
            } catch (Exception ignored) {}
        }
        // Also check superclass fields
        Class<?> superCls = obj.getClass().getSuperclass();
        if (superCls != null) {
            for (Field f : superCls.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val instanceof EditText) return (EditText) val;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String findThreadId(Object obj) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                Object val = f.get(obj);
                // Instagram thread IDs are long numeric strings (17+ digits)
                if (val instanceof String && ((String) val).matches("\\d{10,}")) {
                    return (String) val;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Try known JSON key name first, fall back to ProGuard obfuscated name. */
    private static String reflectString(Object obj, String jsonKey, String obfName) {
        Object val = reflectRaw(obj, jsonKey, obfName);
        return val instanceof String ? (String) val : null;
    }

    private static Object reflectRaw(Object obj, String jsonKey, String obfName) {
        try {
            Field f = obj.getClass().getDeclaredField(jsonKey);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e1) {
            try {
                Field f = obj.getClass().getDeclaredField(obfName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static Object findFieldByType(Object obj, String typeNameHint) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().toLowerCase().contains(typeNameHint.toLowerCase())) {
                f.setAccessible(true);
                try { return f.get(obj); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Object findFieldByNameHint(Object obj, String nameHint) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getName().toLowerCase().contains(nameHint.toLowerCase())) {
                f.setAccessible(true);
                try { return f.get(obj); } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
