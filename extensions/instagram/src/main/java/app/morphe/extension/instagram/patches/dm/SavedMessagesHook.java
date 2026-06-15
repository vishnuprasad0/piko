/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.db.PikoMessageDb;
import app.morphe.extension.instagram.entity.Entity;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Runtime hooks injected at bytecode level by SaveDeletedMessagesPatch.
 *
 * Field names ("A00", "A01", etc.) are ProGuard-obfuscated Instagram internals.
 * Use ObjectBrowser to discover actual field names for the current APK version.
 * Run the app, trigger ObjectBrowser on a DirectItem object, and search for
 * "item_id", "text", "user_id", "timestamp_ms" to find the obfuscated names.
 */
@SuppressWarnings("unused")
public class SavedMessagesHook {

    /**
     * Called when Instagram receives a new DM item from the server
     * (hooked at the point where DirectItem is added to the thread store).
     *
     * @param messageObject  Instagram's internal DirectItem/ThreadItem object
     * @param threadId       The DM thread ID (String)
     */
    public static void onMessageReceived(Object messageObject, String threadId) {
        try {
            if (!Pref.saveDeletedMessages()) return;
            if (messageObject == null || threadId == null) return;

            Entity msg = new Entity(messageObject);

            // item_id field — obfuscated. Verify with ObjectBrowser on DirectItem.
            String messageId = safeGetString(msg, "A00");

            // message_type detail enum — obfuscated nested entity
            String messageType = "unknown";
            try {
                Entity typeDetail = msg.getFieldAsEntity("A01");
                Object typeVal = typeDetail.getField("A00");
                if (typeVal != null) messageType = typeVal.toString();
            } catch (Exception ignored) {}

            // sender_info entity (UserInfo with id + username)
            String senderId = null;
            String senderUsername = null;
            try {
                Entity userInfo = msg.getFieldAsEntity("A02");
                senderId = safeGetString(userInfo, "A00");
                senderUsername = safeGetString(userInfo, "A01");
            } catch (Exception ignored) {}

            // timestamp_ms — may be Long or wrapped numeric
            long timestamp = System.currentTimeMillis();
            try {
                Object ts = msg.getField("A03");
                if (ts instanceof Long) timestamp = (Long) ts;
                else if (ts instanceof Number) timestamp = ((Number) ts).longValue();
            } catch (Exception ignored) {}

            // text content for text messages
            String content = null;
            try {
                Entity textData = msg.getFieldAsEntity("A04");
                content = safeGetString(textData, "A00");
            } catch (Exception ignored) {}

            PikoMessageDb.getInstance(PikoUtils.getContext())
                .insertOrIgnore(messageId, threadId, senderId, senderUsername, content, messageType, timestamp);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageReceived: " + e);
        }
    }

    /**
     * Called when Instagram processes a message unsend/delete event from the server
     * (hooked at the unsend-item handler, which receives threadId + itemId).
     *
     * @param messageId  The item ID of the deleted message
     * @param threadId   The thread the message belonged to
     */
    public static void onMessageDeleted(String messageId, String threadId) {
        try {
            if (!Pref.saveDeletedMessages()) return;
            if (messageId == null) return;

            PikoMessageDb.getInstance(PikoUtils.getContext()).markDeleted(messageId);

        } catch (Exception e) {
            Logger.printException(() -> "SavedMessagesHook.onMessageDeleted: " + e);
        }
    }

    private static String safeGetString(Entity entity, String field) {
        try {
            Object val = entity.getField(field);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
