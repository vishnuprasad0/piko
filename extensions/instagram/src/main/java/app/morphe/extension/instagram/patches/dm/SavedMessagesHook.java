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
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Runtime hooks for the "Save deleted messages" feature.
 *
 * <h2>v426 Architecture — TWO delivery paths, both must be hooked</h2>
 *
 * <pre>
 * REST / JSON (thread history load)
 *   LX/0gL;.parseFromJson(LX/R0r;)LX/9ZA;    ← classes.dex
 *     └─ LX/AtQ;.parse → LX/0gG;.unsafeParseFromJson (creates LX/0gF; instance)
 *   Hook 1 is injected before RETURN_OBJECT in parseFromJson.
 *
 * MQTT / MSys real-time delivery
 *   LX/0gF;.A02(LX/1kP;, ..., LX/02L;, ...)  ← 24-param constructor, builds from delta
 *   LX/0gF;.A0P(UserSession, LX/02L;)LX/0gF;  ← classes12.dex, post-processing step
 *   Hook 2 is injected before the success RETURN_OBJECT in A0P (offset 0351/0352).
 * </pre>
 *
 * MQTT messages (including real-time send + unsend while in-thread) NEVER go through
 * parseFromJson. Without Hook 2, any message sent and unsent while the user is actively
 * in the thread would be missed entirely.
 *
 * <h2>Class hierarchy</h2>
 *
 * {@code LX/0gF;} (PUBLIC FINAL) extends {@code LX/9ZA;} (DirectItem base class).
 * {@code LX/0gF;} has NO additional instance fields — all data fields are declared on
 * {@code LX/9ZA;}. Every {@code getDeclaredField} call must walk the superclass chain
 * (see {@link #getFieldValue}) or it will silently fail when the runtime type is
 * {@code LX/0gF;} and the field is actually on {@code LX/9ZA;}.
 *
 * <h2>v426 field mapping (confirmed from dexdump classes12.dex)</h2>
 *
 * <pre>
 * JSON key        Obfuscated field   Type                   Class
 * item_id         A13                String                 LX/9ZA;
 * hide_in_thread  A1Y                Z (boolean)            LX/9ZA;
 * user_id         A1M                String                 LX/9ZA;
 * timestamp       A1J                String (microseconds)  LX/9ZA;
 * text            A1I                String                 LX/9ZA;
 * item_type       A0Y                LX/8ot; (enum)         LX/9ZA;
 * thread_key      A0W                DirectThreadKey        LX/9ZA;
 * threadId (key)  A00                String                 DirectThreadKey
 * MSys delta ref  A0V                LX/02L;                LX/9ZA;
 * </pre>
 *
 * v408 fallbacks: item_id via getter {@code A0l()}, hide_in_thread as {@code A2V:Z},
 * thread_key fields {@code A16/A18/A15}.
 *
 * <h2>How to update for a new Instagram version</h2>
 *
 * 1. Install the patched APK (pref on). Open any DM thread.
 * 2. In logcat (tag: piko), find "SavedMessagesHook ObjectBrowser dump" — this lists all
 *    fields on the runtime item, including inherited ones from superclasses.
 * 3. If field names differ from the table above, update the constants in
 *    {@link #onMessageReceived} and the table in Fingerprint.kt.
 * 4. If the hook doesn't fire at all, the fingerprint anchors may have changed:
 *    - Hook 1: grep classes.dex for methods with "item_id" + "hide_in_thread" + returnType Z
 *    - Hook 2: grep classes12.dex for "DirectMessage.postprocess" + "null type" string pair
 */
@SuppressWarnings("unused")
public class SavedMessagesHook {

    private static final String BUTTON_TAG = "piko_deleted_msgs_btn";

    // -------------------------------------------------------------------------
    // Hook 1 (REST) + Hook 2 (MQTT): called when any DirectItem is finalized.
    // Hook 1 fires from LX/0gL;.parseFromJson (REST thread-history loads).
    // Hook 2 fires from LX/0gF;.A0P (MQTT/MSys real-time delivery).
    // Both pass the item as Object; reflection extracts v426 fields listed above.
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

            // v426 field names (confirmed from dexdump classes12.dex LX/0gL;.A00):
            //   item_id        → A13:String
            //   user_id        → A1M:String (sender ID)
            //   item_type      → A0Y:enum (toString() for value)
            //   timestamp      → A1J:String (microseconds)
            //   text           → A1I:String
            //   thread_key     → A0W:DirectThreadKey, .A00:String
            // v408 field names (fallback):
            //   item_id        → getter A0l(), thread_key → A16/A18
            String messageId  = reflectString(item, "item_id", "A13");
            if (messageId == null) messageId = reflectStringOrInvoke(item, "item_id", "A0l");
            String threadId   = reflectThreadIdFromItem(item);
            String senderId   = reflectString(item, "user_id", "A1M");
            String senderUser = null;
            String content    = null;
            String type       = null;
            long   timestamp  = System.currentTimeMillis();

            // item_type: v426 stores as enum (A0Y), v408 as String (A0R)
            try {
                Object typeObj = reflectRaw(item, "item_type", "A0Y");
                if (typeObj != null) type = typeObj.toString();
            } catch (Exception ignored) {}
            if (type == null) type = reflectString(item, "item_type", "A0R");

            // timestamp: v426 stores as String microseconds (A1J), v408 as Long (A03)
            try {
                String tsStr = reflectString(item, "timestamp", "A1J");
                if (tsStr != null && !tsStr.isEmpty()) {
                    timestamp = Long.parseLong(tsStr) / 1000L; // µs → ms
                } else {
                    Object ts = reflectRaw(item, "timestamp", "A03");
                    if (ts instanceof Long)   timestamp = (Long) ts;
                    if (ts instanceof Number) timestamp = ((Number) ts).longValue();
                }
            } catch (Exception ignored) {}

            // text content: v426 stores directly on item (A1I), v408 in nested text object
            try {
                content = reflectString(item, "text", "A1I");
                if (content == null) {
                    Object textObj = findFieldByNameHint(item, "text");
                    if (textObj instanceof String) {
                        content = (String) textObj;
                    } else if (textObj != null) {
                        content = reflectString(textObj, "text", "A00");
                    }
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

    /** Read a boolean field by exact name, tolerating Boolean/boolean. Walks the superclass chain. */
    private static boolean readBool(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(obj);
                return v instanceof Boolean && (Boolean) v;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static final java.util.Set<String> DUMPED_CLASSES =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Logs the class name and every declared field (name = value) the first time an
     * unmappable item of a given class is seen — walks the full superclass chain so
     * fields declared on parent classes (e.g. LX/9ZA; when the runtime type is LX/0gF;)
     * are included in the dump.
     */
    private static void dumpUnknownItemOnce(Object item) {
        try {
            if (item == null) return;
            String cls = item.getClass().getName();
            if (!DUMPED_CLASSES.add(cls)) return;
            StringBuilder sb = new StringBuilder("SavedMessagesHook ObjectBrowser dump for ").append(cls).append(":\n");
            Class<?> c = item.getClass();
            while (c != null && c != Object.class) {
                if (c != item.getClass()) sb.append("  [inherited from ").append(c.getName()).append("]\n");
                for (Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(item); } catch (Exception e) { v = "<inaccessible>"; }
                    String vs = v == null ? "null" : v.toString();
                    if (vs.length() > 80) vs = vs.substring(0, 80) + "…";
                    sb.append("  ").append(f.getType().getSimpleName()).append(' ')
                      .append(f.getName()).append(" = ").append(vs).append('\n');
                }
                c = c.getSuperclass();
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
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val instanceof EditText) return (EditText) val;
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
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
     * Try a field by name first (walking superclass chain), then invoke a zero-arg getter method.
     * Used for message-id which in v408+ is behind getter A0l() not a direct field.
     */
    private static String reflectStringOrInvoke(Object obj, String fieldName, String methodName) {
        Object v = getFieldValue(obj, fieldName);
        if (v instanceof String) return (String) v;
        // Try getter method (walk superclass chain for method too)
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                Object r = m.invoke(obj);
                if (r instanceof String) return (String) r;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extract the thread-id string from the DirectItem.
     * v426: thread_key stored in field A0W (DirectThreadKey), threadId is A00:String.
     * v408: thread_key in field A16/A18/A15, threadId is A00:String.
     * Uses getFieldValue to walk the superclass chain (needed when item is LX/0gF;).
     */
    private static String reflectThreadIdFromItem(Object item) {
        for (String keyField : new String[]{"A0W", "A16", "A18", "A15"}) {
            Object key = getFieldValue(item, keyField);
            if (key == null) continue;
            Object v = getFieldValue(key, "A00");
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return null;
    }

    private static Object reflectRaw(Object obj, String jsonKey, String obfName) {
        Object v = getFieldValue(obj, jsonKey);
        if (v != null) return v;
        return getFieldValue(obj, obfName);
    }

    /** Walk the full superclass chain to find and read a field by name. */
    private static Object getFieldValue(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object findFieldByType(Object obj, String typeNameHint) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getSimpleName().toLowerCase().contains(typeNameHint.toLowerCase())) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Object findFieldByNameHint(Object obj, String nameHint) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains(nameHint.toLowerCase())) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}
