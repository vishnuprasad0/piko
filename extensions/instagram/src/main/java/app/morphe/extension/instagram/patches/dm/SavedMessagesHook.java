/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    // Background thread for all Hook 1/2 work so the MQTT delivery thread is never blocked.
    private static android.os.HandlerThread sWorkerThread;
    private static android.os.Handler sWorker;

    private static synchronized android.os.Handler getWorker() {
        if (sWorker == null) {
            sWorkerThread = new android.os.HandlerThread("piko-dm-hook");
            sWorkerThread.start();
            sWorker = new android.os.Handler(sWorkerThread.getLooper());
        }
        return sWorker;
    }

    // Dedup set: item_ids already queued. Checked on calling thread (cheap) to avoid
    // posting duplicate Runnables. Bounded to 2000 entries via eldest-entry eviction.
    private static final java.util.Map<String, Boolean> SEEN_ITEM_IDS =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Boolean>() {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) {
                return size() > 2000;
            }
        });

    public static void onMessageReceived(final Object item) {
        // DIAGNOSTIC: unconditional first-line log to confirm A0P fires at all.
        final String dbgCls = (item == null) ? "null" : item.getClass().getName();
        Logger.printException(() -> "SavedMessagesHook A0P=" + dbgCls);
        // Called from the MQTT thread — must return instantly. All reflection/DB work
        // is posted to sWorker (background HandlerThread).
        if (item == null) return;
        if (!Pref.saveDeletedMessages()) { Logger.printException(() -> "SavedMessagesHook pref=off"); return; }
        // Class guard: only X.* (obfuscated IG classes) are DirectItem candidates.
        if (!item.getClass().getName().startsWith("X.")) { Logger.printException(() -> "SavedMessagesHook cls-fail=" + dbgCls); return; }

        getWorker().post(new Runnable() { @Override public void run() {
            processReceivedItem(item);
        }});
    }

    private static void processReceivedItem(Object item) {
        try {
            // DIAGNOSTIC: prove the hook fires.
            final String cls = item.getClass().getName();
            Logger.printException(() -> "SavedMessagesHook ENTER item=" + cls);
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
            // Dedup: A0P is called for every historical inbox item on each sync.
            if (messageId != null && SEEN_ITEM_IDS.put(messageId, Boolean.TRUE) != null) return;
            String threadId   = reflectThreadIdFromItem(item);
            String senderId   = reflectString(item, "user_id", "A1M");
            String senderUser = resolveSenderUsername(item, senderId);
            // MQTT path only delivers sender_id, not a full UserInfo object.
            // Try to resolve username from IG's user cache; fall back to sender_id.
            if (senderUser == null && senderId != null) {
                senderUser = resolveUsernameFromCache(senderId);
            }
            // DIAGNOSTIC: log UserInfo candidates when username not found.
            if (senderUser == null) { dumpUserInfoCandidatesOnce(item, senderId); }
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

            // text content:
            //   REST path (X.9ZA base): A1I:String
            //   MQTT path (X.0gF wrapper): A0o:Object (confirmed by Frida — "Hi" etc.)
            //   v408: nested text object with A00:String
            try {
                content = reflectString(item, "text", "A1I");
                if (content == null) {
                    // MQTT wrapper (X.0gF) stores text in A0o on the subclass, not on X.9ZA
                    Object mqttText = getFieldValue(item, "A0o");
                    if (mqttText instanceof String) content = (String) mqttText;
                }
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
                // The legacy obfuscated id fields (A13/A0l) do NOT resolve on the MQTT
                // subclass (X.0gF) — there A13 is a boolean, not item_id — so modern
                // (E2EE) DMs would silently fall through here and never be stored,
                // leaving the deleted-messages list permanently empty.
                //
                // Instead of dropping the message, derive a stable synthetic key from
                // sender + timestamp (the same dedupe key scripts/frida/dm-hooks.js uses).
                // This guarantees every received item is captured and, critically, that a
                // later unsend of the same item maps back to the same row to mark deleted.
                //
                // Still dump the object once so the real obfuscated id field can be
                // confirmed and wired in (see Fix B / SgMessage path).
                dumpUnknownItemOnce(item);
                if (senderId != null) {
                    messageId = "syn:" + senderId + ":" + timestamp;
                } else {
                    // No id and no sender — nothing we can key on reliably; skip.
                    return;
                }
            }

            // thread_id is NOT NULL in the schema; fall back to empty when unknown.
            if (threadId == null) threadId = "";

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

                // Anti-revoke in-place: undo the deletion flag on the item object so IG
                // keeps the message visible in the thread with its original text.
                // content is null on the unsend event (text is stripped before delivery),
                // so we look up the previously-stored text from the DB vault.
                String storedContent = (content != null && !content.isEmpty())
                        ? content : db.getStoredContent(messageId);
                antiRevokeItem(item, storedContent);
            } else {
                db.insertOrIgnore(messageId, threadId, senderId, senderUser, content, type, timestamp);
            }

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.processReceivedItem: " + e);
        }
    }

    /**
     * Anti-revoke in-place: reset the hide_in_thread flag and restore text so IG's thread
     * UI renders the message normally instead of hiding it. The item object is mutated
     * in place — the caller's return-object smali instruction sees the modified state.
     *
     * Two text paths must be restored (confirmed by Frida on v426):
     *   REST path (LX/9ZA; base class): text at A1I:String
     *   MQTT path (LX/0gF; subclass):   text at A0o:Object (holds the String directly)
     * Both must be set; if only A1I is set, MQTT-delivered items still appear unsent
     * because Instagram reads from A0o when the runtime type is the MQTT subclass.
     */
    private static void antiRevokeItem(Object item, String restoredContent) {
        // Reset hide_in_thread (all known obfuscated names + proto stable name).
        setField(item, "A1Y", false);           // v426 LX/9ZA; / v4xx LX/9wl;
        setField(item, "A2V", false);           // v408 LX/5jI;
        setField(item, "hideInThread_", false); // proto model stable name

        // Restore original text to BOTH text fields.
        if (restoredContent != null) {
            setField(item, "A1I", restoredContent); // v426 REST text field (base class)
            setField(item, "A0o", restoredContent); // v426 MQTT text field (subclass A0o:Object)
        }
    }

    /** Set a field by name on obj, walking the superclass chain. Silently ignores missing fields. */
    private static void setField(Object obj, String fieldName, Object value) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception ignored) {
                return;
            }
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
    // Hook 4: fires at entry of the SQLite DAO method that hides a DirectItem.
    //
    // We receive p0 (the DAO), p2 (server_item_id), p3 (client_item_id).
    // The hook fires BEFORE the DELETE, so Instagram's "messages" table still
    // has the row with text, thread_id, timestamp, message_type, etc.
    //
    // Instagram DB access (smali analysis of LX/0HR;, the direct-msg DB helper):
    //   LX/0HR; extends SQLiteOpenHelper; holds A00:SQLiteDatabase (instance field).
    //   LX/0HR;.A06 = static connection-manager (LX/0HS;) → .A00() → LX/0HR; instance.
    //   Table: "messages"; columns: server_item_id, client_item_id, text,
    //                               thread_id, user_id, timestamp, message_type.
    // -------------------------------------------------------------------------
    public static void onMessageHiddenFromDb(Object dao, String serverId, String clientId) {
        try {
            if (!Pref.saveDeletedMessages()) return;

            String itemId = (serverId != null && !serverId.isEmpty()) ? serverId : clientId;
            if (itemId == null) return;

            // DIAGNOSTIC: dump DAO fields once to find message cache / SQLiteDatabase handle.
            dumpDaoOnce(dao, serverId, clientId);

            String content     = null;
            String threadId    = "";
            String senderId    = null;
            String messageType = "text";
            long   timestamp   = System.currentTimeMillis();

            // DIAGNOSTIC (one-shot): dump recent rows from the MSYS deleted-messages table so we
            // can confirm the deleted_message_payload blob is text-extractable (v426 MSYS).
            dumpDeletedPayloadOnce(PikoUtils.getContext());

            // --- Read from Instagram's own "messages" table before the DELETE fires ---
            SQLiteDatabase igDb = getInstagramDb(dao);
            boolean openedFresh = false; // true if WE opened the handle (must close after)
            if (igDb == null) {
                igDb = openInstagramDbFile(PikoUtils.getContext());
                openedFresh = (igDb != null);
            }
            if (igDb != null) {
                try {
                    String   where;
                    String[] args;
                    if (serverId != null && !serverId.isEmpty() && clientId != null && !clientId.isEmpty()) {
                        where = "server_item_id = ? OR client_item_id = ?";
                        args  = new String[]{serverId, clientId};
                    } else if (serverId != null && !serverId.isEmpty()) {
                        where = "server_item_id = ?";
                        args  = new String[]{serverId};
                    } else {
                        where = "client_item_id = ?";
                        args  = new String[]{clientId};
                    }
                    Cursor c = igDb.query(
                        "messages",
                        new String[]{"text", "thread_id", "user_id", "timestamp", "message_type"},
                        where, args, null, null, null, "1");
                    if (c != null) {
                        if (c.moveToFirst()) {
                            content     = c.getString(0);
                            String tId  = c.getString(1);
                            if (tId  != null && !tId.isEmpty())  threadId    = tId;
                            senderId    = c.getString(2);
                            String ts   = c.getString(3);
                            if (ts   != null && !ts.isEmpty()) {
                                try { timestamp = Long.parseLong(ts) / 1000L; } catch (Exception ignored) {}
                            }
                            String mt   = c.getString(4);
                            if (mt   != null) messageType = mt;
                        }
                        c.close();
                    }
                } finally {
                    if (openedFresh) igDb.close();
                }
            }

            PikoMessageDb vault = PikoMessageDb.getInstance(PikoUtils.getContext());

            if (content != null && !content.isEmpty()) {
                vault.insertOrIgnore(itemId, threadId, senderId, null, content, messageType, timestamp);
            } else {
                // Media / unsupported type — fall back to what Hook 1/2 may have stored.
                String stored = vault.getStoredContent(itemId);
                if (stored != null) content = stored;
                // Still insert a skeleton row so markDeleted has a row to update.
                vault.insertOrIgnore(itemId, threadId, senderId, null,
                        describeMediaType(messageType), messageType, timestamp);
            }
            vault.markDeleted(itemId);

            // DIAGNOSTIC: log what ended up stored in PikoMessageDb for this deletion.
            String pikoContent = vault.getStoredContent(itemId);
            final String dbgId = itemId.length() > 8 ? itemId.substring(0, 8) : itemId;
            Logger.printException(() -> "Hook4 id=" + dbgId + " piko=" + pikoContent);

            String notifBody = (pikoContent != null && !pikoContent.isEmpty())
                    ? pikoContent : describeMediaType(messageType);
            // Try to get sender info from PikoMessageDb (stored by Hook 2).
            String storedSender = vault.getSenderDisplay(itemId);
            notifyDeletion(storedSender, notifBody, messageType);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageHiddenFromDb: " + e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean USER_INFO_DUMPED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void dumpUserInfoCandidatesOnce(Object item, String senderId) {
        if (!USER_INFO_DUMPED.compareAndSet(false, true)) return;
        try {
            Logger.printException(() -> "UINF senderId=" + senderId + " cls=" + item.getClass().getName());
            // Dump all non-primitive fields on the item and its superclasses so we can
            // identify which field holds the UserInfo object (for username extraction).
            Class<?> cls = item.getClass();
            while (cls != null && cls != Object.class) {
                final String cn = cls.getName();
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == String.class || f.getType() == java.lang.Boolean.class
                            || f.getType() == java.lang.Long.class || f.getType() == java.lang.Integer.class) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(item); } catch (Exception e2) { continue; }
                    if (v == null) continue;
                    final String fn = f.getName();
                    final String vt = v.getClass().getName();
                    Logger.printException(() -> "UINF " + cn + "." + fn + ":" + vt);
                    // If this object has any String fields, log them (candidate username).
                    for (Field sf : v.getClass().getDeclaredFields()) {
                        if (sf.getType() != String.class) continue;
                        sf.setAccessible(true);
                        Object sv;
                        try { sv = sf.get(v); } catch (Exception e2) { continue; }
                        if (sv instanceof String && !((String)sv).isEmpty()) {
                            final String sfn = sf.getName();
                            final String svv = (String) sv;
                            Logger.printException(() -> "UINF   ." + sfn + "=" + svv);
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Logger.printException(() -> "UINF dump failed: " + e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean DAO_DUMPED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-shot: dump all fields of the DAO object (and superclasses) to find message cache or DB handle. */
    private static void dumpDaoOnce(Object dao, String serverId, String clientId) {
        if (!DAO_DUMPED.compareAndSet(false, true)) return;
        try {
            Logger.printException(() -> "DAODUMP serverId=" + serverId + " clientId=" + clientId
                    + " dao=" + (dao == null ? "null" : dao.getClass().getName()));
            if (dao == null) return;
            Class<?> cls = dao.getClass();
            while (cls != null && !cls.equals(Object.class)) {
                final String clsName = cls.getName();
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val;
                    try { val = f.get(dao); } catch (Exception ex) { val = "<err:" + ex.getMessage() + ">"; }
                    final String fieldDesc = "  " + clsName + "." + f.getName() + ":" + f.getType().getSimpleName() + " = " + summarize(val);
                    Logger.printException(() -> "DAODUMP " + fieldDesc);
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Logger.printException(() -> "DAODUMP error: " + e);
        }
    }

    private static String summarize(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + val + "\"";
        if (val instanceof SQLiteDatabase) return "<SQLiteDatabase path=" + ((SQLiteDatabase)val).getPath() + ">";
        String s = val.toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean DB_LAYOUT_DUMPED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * One-shot diagnostic: walk every *.db under the app's databases dir, list each DB's tables,
     * and for any table whose name/columns look message-related, log its columns. Lets us find
     * IG's real DM store (path + table + columns) on v426/Android 15 from inside the app process,
     * since the app is not debuggable and run-as is blocked.
     */
    private static void dumpDatabasesLayoutOnce(Context ctx) {
        try {
            if (ctx == null) return;
            if (!DB_LAYOUT_DUMPED.compareAndSet(false, true)) return;
            File dbDir = ctx.getDatabasePath("x").getParentFile();
            if (dbDir == null || !dbDir.exists()) {
                Logger.printException(() -> "DBLAYOUT: databases dir missing: " + dbDir);
                return;
            }
            File[] all = dbDir.listFiles();
            StringBuilder sb = new StringBuilder("DBLAYOUT dir=").append(dbDir).append('\n');
            if (all != null) {
                for (File f : all) {
                    sb.append("  FILE ").append(f.getName()).append(" (").append(f.length()).append(" b)\n");
                    if (!f.getName().endsWith(".db")) continue;
                    SQLiteDatabase db = null;
                    try {
                        db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                        Cursor tc = db.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table'", null);
                        while (tc.moveToNext()) {
                            String table = tc.getString(0);
                            sb.append("      TABLE ").append(table);
                            String lt = table.toLowerCase();
                            if (lt.contains("message") || lt.contains("thread") || lt.contains("item")
                                    || lt.contains("user") || lt.contains("msys")) {
                                try {
                                    Cursor cc = db.rawQuery("PRAGMA table_info(" + table + ")", null);
                                    sb.append(" [");
                                    while (cc.moveToNext()) sb.append(cc.getString(1)).append(',');
                                    sb.append(']');
                                    cc.close();
                                } catch (Exception ignored) {}
                            }
                            sb.append('\n');
                        }
                        tc.close();
                    } catch (Exception e) {
                        sb.append("      <open failed: ").append(e).append(">\n");
                    } finally {
                        if (db != null) db.close();
                    }
                }
            }
            final String out = sb.toString();
            Logger.printException(() -> out);
        } catch (Exception e) {
            Logger.printException(() -> "dumpDatabasesLayoutOnce: " + e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean DEL_PAYLOAD_DUMPED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Locate the MSYS reverb_db (holds local_message_persistence_store*). WAL → concurrent read OK. */
    private static File findReverbDb(Context ctx) {
        if (ctx == null) return null;
        File dbDir = ctx.getDatabasePath("x").getParentFile();
        if (dbDir == null) return null;
        File[] all = dbDir.listFiles();
        if (all == null) return null;
        for (File f : all) {
            if (f.getName().startsWith("reverb_db_") && f.getName().endsWith(".db")) return f;
        }
        return null;
    }

    /** Extract printable ASCII runs (length >= 3) from a blob, joined by '|'. */
    private static String printableRuns(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte value : b) {
            int c = value & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                run.append((char) c);
            } else {
                if (run.length() >= 3) { if (sb.length() > 0) sb.append('|'); sb.append(run); }
                run.setLength(0);
            }
        }
        if (run.length() >= 3) { if (sb.length() > 0) sb.append('|'); sb.append(run); }
        return sb.toString();
    }

    private static void dumpDeletedPayloadOnce(Context ctx) {
        try {
            if (!DEL_PAYLOAD_DUMPED.compareAndSet(false, true)) return;
            File reverb = findReverbDb(ctx);
            if (reverb == null) { Logger.printException(() -> "DELPAYLOAD: reverb_db not found"); return; }
            SQLiteDatabase db = SQLiteDatabase.openDatabase(reverb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                // Log all reverb_db files found (there may be multiple per account).
                File dbDir = ctx.getDatabasePath("x").getParentFile();
                if (dbDir != null) {
                    File[] all = dbDir.listFiles();
                    if (all != null) {
                        StringBuilder allDbs = new StringBuilder("DELPAYLOAD all reverb_dbs: ");
                        for (File f : all) { if (f.getName().startsWith("reverb_db_")) allDbs.append(f.getName()).append(" "); }
                        final String allDbsOut = allDbs.toString();
                        Logger.printException(() -> allDbsOut);
                    }
                }

                // Schema: log one entry per table with row count so logcat doesn't truncate.
                Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null);
                while (tables.moveToNext()) {
                    String tbl = tables.getString(0);
                    Cursor cnt = db.rawQuery("SELECT COUNT(*) FROM " + tbl, null);
                    long rowCnt = cnt.moveToFirst() ? cnt.getLong(0) : -1;
                    cnt.close();
                    Cursor cols = db.rawQuery("PRAGMA table_info(" + tbl + ")", null);
                    StringBuilder tblInfo = new StringBuilder("DELPAYLOAD TABLE ").append(tbl).append(" (").append(rowCnt).append(" rows) cols: ");
                    while (cols.moveToNext()) { tblInfo.append(cols.getString(1)).append(":").append(cols.getString(2)).append(" "); }
                    cols.close();
                    final String tblOut = tblInfo.toString();
                    Logger.printException(() -> tblOut);
                }
                tables.close();

                // Dump last 3 rows from main messages table (message is still there when Hook 4 fires).
                dumpTableRows(db, "local_message_persistence_store", "rowid DESC");
                // Also try deleted table in case IG already wrote it.
                dumpTableRows(db, "local_message_persistence_store_deleted_messages", "deletion_timestamp_ms DESC");
            } finally {
                db.close();
            }
        } catch (Exception e) {
            Logger.printException(() -> "dumpDeletedPayloadOnce: " + e);
        }
    }

    private static void dumpTableRows(SQLiteDatabase db, String table, String order) {
        try {
            Cursor c = db.query(table, null, null, null, null, null, order, "3");
            String[] cols = c.getColumnNames();
            int rowNum = 0;
            while (c.moveToNext()) {
                rowNum++;
                final int rn = rowNum;
                StringBuilder sb = new StringBuilder("DELPAYLOAD ").append(table).append(" row").append(rn).append(":\n");
                for (int i = 0; i < cols.length; i++) {
                    int type = c.getType(i);
                    if (type == Cursor.FIELD_TYPE_BLOB) {
                        byte[] blob = c.getBlob(i);
                        sb.append("  ").append(cols[i]).append(" (blob ")
                          .append(blob == null ? 0 : blob.length).append("b): ")
                          .append(printableRuns(blob)).append('\n');
                    } else {
                        sb.append("  ").append(cols[i]).append(" = ").append(c.getString(i)).append('\n');
                    }
                }
                final String rowOut = sb.toString();
                Logger.printException(() -> rowOut);
            }
            if (rowNum == 0) Logger.printException(() -> "DELPAYLOAD " + table + ": 0 rows");
            c.close();
        } catch (Exception e) {
            Logger.printException(() -> "DELPAYLOAD dumpTableRows(" + table + "): " + e);
        }
    }

    private static String describeMediaType(String type) {
        if (type == null) return "[deleted]";
        switch (type) {
            case "image":           return "[photo deleted]";
            case "video":           return "[video deleted]";
            case "voice_media":
            case "audio":           return "[voice message deleted]";
            case "animated_media":  return "[GIF deleted]";
            case "reel_share":      return "[reel deleted]";
            case "story_share":     return "[story reply deleted]";
            case "media_share":     return "[post share deleted]";
            case "like":            return "[like deleted]";
            case "link":            return "[link deleted]";
            case "action_log":      return "[activity deleted]";
            default:                return "[" + type + " deleted]";
        }
    }

    /**
     * Returns Instagram's live SQLiteDatabase by scanning the DB helper's static singleton.
     *
     * Access chain (confirmed from smali of Instagram's direct-message DB helper, LX/0HR;):
     *   - LX/0HR; extends SQLiteOpenHelper and holds A00:SQLiteDatabase (instance field)
     *   - LX/0HR;.A06 is a static field holding the connection-manager (LX/0HS;)
     *   - LX/0HS;.A00() returns the LX/0HR; open-helper instance
     *
     * We load the class by binary name ("X.0HR") and scan its fields by TYPE rather than
     * by hardcoded names so this survives minor ProGuard rename variations.
     */
    private static SQLiteDatabase getInstagramDb(Object dao) {
        try {
            ClassLoader cl = dao.getClass().getClassLoader();
            Class<?> helperClass = null;
            try { helperClass = cl.loadClass("X.0HR"); } catch (Exception ignored) {}

            // If name-load failed, walk DAO superclass static fields for the same pattern.
            if (helperClass == null) {
                SQLiteDatabase db = scanForDb(dao.getClass());
                if (db != null) return db;
                return null;
            }

            return scanForDb(helperClass);
        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.getInstagramDb: " + e);
            return null;
        }
    }

    /** Scans static fields of cls for a singleton that, via a no-arg method, yields
     *  an object holding a SQLiteDatabase instance field. */
    private static SQLiteDatabase scanForDb(Class<?> cls) {
        for (Field sf : cls.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(sf.getModifiers())) continue;
            if (sf.getType().isPrimitive()) continue;
            sf.setAccessible(true);
            Object mgr;
            try { mgr = sf.get(null); } catch (Exception e) { continue; }
            if (mgr == null) continue;
            for (Method m : mgr.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() == Void.TYPE) continue;
                m.setAccessible(true);
                Object helper;
                try { helper = m.invoke(mgr); } catch (Exception e) { continue; }
                if (helper == null) continue;
                for (Field df : helper.getClass().getDeclaredFields()) {
                    if (!SQLiteDatabase.class.isAssignableFrom(df.getType())) continue;
                    df.setAccessible(true);
                    Object db;
                    try { db = df.get(helper); } catch (Exception e) { continue; }
                    if (db instanceof SQLiteDatabase && ((SQLiteDatabase) db).isOpen())
                        return (SQLiteDatabase) db;
                }
            }
        }
        return null;
    }

    /**
     * Fallback: open a read-only handle to Instagram's direct-message DB file.
     * Scans the app's databases dir and verifies the "messages" table exists.
     * WAL mode (enabled by Instagram) allows concurrent readers — safe to open.
     * Caller must close() the returned database.
     */
    private static SQLiteDatabase openInstagramDbFile(Context ctx) {
        if (ctx == null) return null;
        File dbDir = ctx.getDatabasePath("x").getParentFile();
        if (dbDir == null || !dbDir.exists()) return null;
        java.util.List<File> candidates = new java.util.ArrayList<>();
        for (String n : new String[]{"direct.db", "direct_side_panel.db", "direct_bootstrap.db"}) {
            File f = new File(dbDir, n); if (f.exists()) candidates.add(f);
        }
        File[] all = dbDir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(".db") && !candidates.contains(f)) candidates.add(f);
            }
        }
        for (File f : candidates) {
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(
                        f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor chk = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='messages'", null);
                boolean ok = chk.moveToFirst(); chk.close();
                if (ok) return db;
                db.close();
            } catch (Exception ignored) {}
        }
        Logger.printException(() -> "SavedMessagesHook: Instagram DM database not found in " + dbDir);
        return null;
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

    /**
     * Tries to resolve a real username for senderId by querying Instagram's local user
     * cache databases. IG stores DM participant profiles in SQLite (user.db or similar).
     * Returns null if the DB isn't found or the user isn't cached.
     */
    private static String resolveUsernameFromCache(String senderId) {
        try {
            Context ctx = PikoUtils.getContext();
            if (ctx == null) return null;
            File dbDir = ctx.getDatabasePath("x").getParentFile();
            if (dbDir == null) return null;
            String[] candidates = {"user.db", "users.db", "igdb.db", "instagram.db",
                                   "user_cache.db", "profile.db", "direct_bootstrap.db"};
            for (String name : candidates) {
                File f = new File(dbDir, name);
                if (!f.exists()) continue;
                try {
                    SQLiteDatabase db = SQLiteDatabase.openDatabase(
                            f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                    for (String[] spec : new String[][]{
                            {"users", "username", "pk"},
                            {"users", "username", "id"},
                            {"user", "username", "pk"},
                            {"user", "username", "user_id"},
                            {"participants", "username", "pk"},
                    }) {
                        try {
                            Cursor c = db.query(spec[0], new String[]{spec[1]},
                                    spec[2] + " = ?", new String[]{senderId},
                                    null, null, null, "1");
                            if (c != null) {
                                String uname = null;
                                if (c.moveToFirst()) uname = c.getString(0);
                                c.close();
                                if (uname != null && !uname.isEmpty()) { db.close(); return uname; }
                            }
                        } catch (Exception ignored) {}
                    }
                    db.close();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Logger.printException(() -> "resolveUsernameFromCache: " + e);
        }
        return null;
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

    /**
     * Resolve the sender's username by reflecting the sender UserInfo entity off the DirectItem.
     *
     * Historic mapping (see CLAUDE.md "Obfuscated field mapping pattern"):
     *   DirectItem.A02 → sender UserInfo entity, with sub-fields A00 = user_id, A01 = username.
     * Obfuscated names rotate per build, so we DON'T rely on a single hardcoded name. Strategy:
     *   1. Try known/candidate UserInfo field names on the item (walking the superclass chain).
     *   2. If none resolve, scan fields by type for a "UserInfo"/"User" object near the user_id.
     *   3. On the UserInfo object, try candidate username sub-fields, then fall back to a
     *      heuristic: a String field that is NOT all-digits (user_id is numeric) and looks like
     *      a handle. The matching user_id confirms we picked the right entity.
     *
     * Returns null if no username can be found (caller stores empty; UI falls back to sender_id).
     * The first time an item shape is seen, dumpUnknownItemOnce() logs every field so the exact
     * v426 names can be confirmed from logcat (tag: piko) and pinned in CANDIDATE_* below.
     */
    private static String resolveSenderUsername(Object item, String senderId) {
        try {
            if (item == null) return null;

            // 1 + 2: locate the sender UserInfo entity.
            Object userInfo = null;
            for (String f : CANDIDATE_USERINFO_FIELDS) {
                Object v = getFieldValue(item, f);
                if (v != null && looksLikeUserInfo(v, senderId)) { userInfo = v; break; }
            }
            if (userInfo == null) {
                Object byType = findFieldByType(item, "userinfo");
                if (looksLikeUserInfo(byType, senderId)) userInfo = byType;
            }
            if (userInfo == null) {
                Object byType = findFieldByType(item, "user");
                if (looksLikeUserInfo(byType, senderId)) userInfo = byType;
            }
            if (userInfo == null) return null;

            // 3: read the username sub-field.
            for (String f : CANDIDATE_USERNAME_FIELDS) {
                Object v = getFieldValue(userInfo, f);
                if (isUsernameLike(v)) return (String) v;
            }
            // Heuristic fallback: first non-numeric String field on the UserInfo object.
            Class<?> cls = userInfo.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(userInfo); } catch (Exception e) { continue; }
                    if (isUsernameLike(v)) return (String) v;
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // v426 candidate field names — confirm/extend from the ObjectBrowser dump in logcat.
    private static final String[] CANDIDATE_USERINFO_FIELDS = {"A02", "A1L", "A0X", "A0Z"};
    private static final String[] CANDIDATE_USERNAME_FIELDS = {"A01", "A0a", "A0Y", "username"};

    /** A UserInfo-like object exposes the matching user_id (when known) on a sub-field. */
    private static boolean looksLikeUserInfo(Object obj, String senderId) {
        if (obj == null) return false;
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) return false;
        if (senderId == null || senderId.isEmpty()) return true; // can't cross-check; accept candidate
        for (String f : new String[]{"A00", "A01", "id", "pk", "user_id"}) {
            Object v = getFieldValue(obj, f);
            if (v != null && senderId.equals(v.toString())) return true;
        }
        return false;
    }

    /** A plausible username: non-empty String that is not purely numeric (those are IDs). */
    private static boolean isUsernameLike(Object v) {
        if (!(v instanceof String)) return false;
        String s = ((String) v).trim();
        return !s.isEmpty() && !s.matches("\\d+");
    }

    /** Try known JSON key name first, fall back to ProGuard obfuscated name. */
    private static String reflectString(Object obj, String jsonKey, String obfName) {
        // Use type-aware lookup: if the obfuscated name maps to a boolean on the concrete
        // class but a String on a superclass (e.g. A13 on LX/0gF; vs LX/9ZA;), skip the
        // wrong-type field and find the String. Falls back to plain lookup for the jsonKey.
        Object val = getFieldValueByType(obj, obfName, String.class);
        if (val == null) val = getFieldValue(obj, jsonKey);
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

    /**
     * Like getFieldValue but skips fields whose declared type is not assignable from
     * expectedType. Needed when the same obfuscated field name appears on both the
     * concrete class (wrong type, e.g. boolean A13 on LX/0gF;) and a superclass
     * (correct type, e.g. String A13 on LX/9ZA;). Plain getFieldValue would return
     * the concrete-class version first, giving the wrong value.
     */
    private static Object getFieldValueByType(Object obj, String fieldName, Class<?> expectedType) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                if (expectedType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (IllegalAccessException ignored) {}
                }
                // Wrong declared type or inaccessible — skip to superclass
            } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
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
