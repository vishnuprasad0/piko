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

            // DIAGNOSTIC: prove the hook fires and reveal the real object shape.
            // printException always emits (unaffected by debug-log setting).
            final String dcls = item.getClass().getName();
            Logger.printException(() -> "SavedMessagesHook ENTER item=" + dcls);
            dumpUnknownItemOnce(item);

            Entity msg = new Entity(item);

            // messageId: JSON key "item_id" maps to server_item_id accessor A0l() in v408/v426.
            // threadId: held in a DirectThreadKey sub-object; the key's String field is A00.
            String messageId  = reflectStringOrInvoke(item, "item_id", "A0l");
            String threadId   = reflectThreadIdFromItem(item);
            String senderId   = null;
            String senderUser = null;
            String content    = null;
            String type       = reflectString(item, "item_type", "A0R");
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

            if (messageId == null) {
                // Field mapping unknown on this build: dump the object once so the
                // exact obfuscated field names can be read from logcat (ObjectBrowser-lite).
                dumpUnknownItemOnce(item);
                return;
            }

            PikoMessageDb db = PikoMessageDb.getInstance(PikoUtils.getContext());

            // Deletion (unsend) detection.
            // hideInThread field on the domain DirectItem object is ProGuard-obfuscated:
            //   v426 (LX/9ZA): A1Y:Z  ← confirmed by static RE of v426 smali
            //   v408 (LX/5jI): A2V:Z  ← confirmed from reference APK analysis
            // Try obfuscated names first (fast path), then fall back to the stable
            // protobuf field name "hideInThread_" in case a future build de-obfuscates.
            boolean deleted = readBool(item, "A1Y")          // v426
                || readBool(item, "A2V")                     // v408 / reference APK
                || readBool(item, "hideInThread_")           // proto-model stable name
                || readBool(item, "is_deleted_for_self");    // sibling flag

            if (deleted) {
                // Capture content first (so the row exists), then mark + notify.
                db.insertOrIgnore(messageId, threadId, senderId, senderUser, content, type, timestamp);
                db.markDeleted(messageId);
                notifyDeletion(senderUser, content, type);
            } else {
                db.insertOrIgnore(messageId, threadId, senderId, senderUser, content, type, timestamp);
            }

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageReceived: " + e);
        }
    }

    /** Read a boolean field by exact name, tolerating Boolean/boolean. Returns false if absent. */
    private static boolean readBool(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v instanceof Boolean && (Boolean) v;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final java.util.Set<String> DUMPED_CLASSES =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Logs the class name and every declared field (name = value) the first time an
     * unmappable item of a given class is seen. Use the logcat output (tag: piko) to
     * identify the obfuscated item_id / hide_in_thread / sender / text fields for this
     * Instagram version, then wire them into the reflection above.
     */
    private static void dumpUnknownItemOnce(Object item) {
        try {
            if (item == null) return;
            String cls = item.getClass().getName();
            if (!DUMPED_CLASSES.add(cls)) return;
            StringBuilder sb = new StringBuilder("SavedMessagesHook ObjectBrowser dump for ").append(cls).append(":\n");
            for (Field f : item.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v;
                try { v = f.get(item); } catch (Exception e) { v = "<inaccessible>"; }
                String vs = v == null ? "null" : v.toString();
                if (vs.length() > 80) vs = vs.substring(0, 80) + "…";
                sb.append("  ").append(f.getType().getSimpleName()).append(' ')
                  .append(f.getName()).append(" = ").append(vs).append('\n');
            }
            String out = sb.toString();
            Logger.printException(() -> out);
        } catch (Exception ignored) {}
    }

    /** Post a system notification when a received message is detected as unsent. */
    private static void notifyDeletion(String sender, String content, String type) {
        try {
            Context ctx = PikoUtils.getContext();
            if (ctx == null) return;

            android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String channelId = "piko_deleted_messages";
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    channelId, "Deleted messages", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Notifies when a received message is unsent");
                nm.createNotificationChannel(ch);
            }

            String who = (sender != null && !sender.isEmpty()) ? "@" + sender : "Someone";
            String body = (content != null && !content.isEmpty()) ? content : "[" + type + "]";

            Intent intent = new Intent(ctx, DeletedMessagesActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, intent, piFlags);

            int iconRes = ctx.getApplicationInfo().icon;
            android.app.Notification.Builder b =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? new android.app.Notification.Builder(ctx, channelId)
                    : new android.app.Notification.Builder(ctx);
            android.app.Notification n = b
                .setSmallIcon(iconRes != 0 ? iconRes : android.R.drawable.ic_dialog_info)
                .setContentTitle(who + " unsent a message")
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.notifyDeletion: " + e);
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

    /**
     * Try a field by name first, then invoke a zero-arg getter method.
     * Used for message-id which in v408+ is behind getter A0l() not a direct field.
     */
    private static String reflectStringOrInvoke(Object obj, String fieldName, String methodName) {
        // 1. Try field by name
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {}
        // 2. Try getter method
        try {
            java.lang.reflect.Method m = obj.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extract the thread-id string from the DirectItem.
     * In v408/v426 the thread is stored in a DirectThreadKey sub-object (field A16 / A18).
     * The key's String id field is named A00. Fall back to scanning String fields.
     */
    private static String reflectThreadIdFromItem(Object item) {
        // Try known sub-object field names for DirectThreadKey
        for (String keyField : new String[]{"A16", "A18", "A15"}) {
            try {
                Field f = item.getClass().getDeclaredField(keyField);
                f.setAccessible(true);
                Object key = f.get(item);
                if (key == null) continue;
                // DirectThreadKey.A00 is the thread-id string
                try {
                    Field idField = key.getClass().getDeclaredField("A00");
                    idField.setAccessible(true);
                    Object v = idField.get(key);
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return null;
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
