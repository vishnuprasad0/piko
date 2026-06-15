/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class PikoMessageDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "piko_dm_vault.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "saved_messages";

    private static volatile PikoMessageDb instance;

    public static PikoMessageDb getInstance(Context context) {
        if (instance == null) {
            synchronized (PikoMessageDb.class) {
                if (instance == null) {
                    instance = new PikoMessageDb(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PikoMessageDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "message_id TEXT UNIQUE NOT NULL," +
            "thread_id TEXT NOT NULL," +
            "sender_id TEXT," +
            "sender_username TEXT," +
            "content TEXT," +
            "message_type TEXT," +
            "timestamp INTEGER NOT NULL," +
            "is_deleted INTEGER DEFAULT 0" +
            ")"
        );
        db.execSQL("CREATE INDEX idx_thread_id ON " + TABLE + "(thread_id)");
        db.execSQL("CREATE INDEX idx_is_deleted ON " + TABLE + "(is_deleted)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void insertOrIgnore(String messageId, String threadId, String senderId,
                               String senderUsername, String content, String type, long timestamp) {
        if (messageId == null || threadId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("message_id", messageId);
        cv.put("thread_id", threadId);
        cv.put("sender_id", senderId);
        cv.put("sender_username", senderUsername != null ? senderUsername : "");
        cv.put("content", content != null ? content : "");
        cv.put("message_type", type != null ? type : "unknown");
        cv.put("timestamp", timestamp);
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void markDeleted(String messageId) {
        if (messageId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("is_deleted", 1);
        db.update(TABLE, cv, "message_id = ?", new String[]{messageId});
    }

    // Returns [messageId, threadId, senderUsername, content, messageType, timestamp]
    public List<String[]> getDeletedMessages() {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, "is_deleted = 1", null, null, null, "timestamp DESC");
        while (c.moveToNext()) {
            result.add(rowToStringArray(c));
        }
        c.close();
        return result;
    }

    public List<String[]> getDeletedMessagesForThread(String threadId) {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, "is_deleted = 1 AND thread_id = ?",
            new String[]{threadId}, null, null, "timestamp DESC");
        while (c.moveToNext()) {
            result.add(rowToStringArray(c));
        }
        c.close();
        return result;
    }

    // Returns [messageId, threadId, senderUsername, content, messageType, timestamp, isDeleted]
    public List<String[]> getAllMessages() {
        List<String[]> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "timestamp DESC");
        while (c.moveToNext()) {
            result.add(rowToStringArrayFull(c));
        }
        c.close();
        return result;
    }

    private String[] rowToStringArray(Cursor c) {
        return new String[]{
            c.getString(c.getColumnIndexOrThrow("message_id")),
            c.getString(c.getColumnIndexOrThrow("thread_id")),
            c.getString(c.getColumnIndexOrThrow("sender_username")),
            c.getString(c.getColumnIndexOrThrow("content")),
            c.getString(c.getColumnIndexOrThrow("message_type")),
            String.valueOf(c.getLong(c.getColumnIndexOrThrow("timestamp")))
        };
    }

    private String[] rowToStringArrayFull(Cursor c) {
        return new String[]{
            c.getString(c.getColumnIndexOrThrow("message_id")),
            c.getString(c.getColumnIndexOrThrow("thread_id")),
            c.getString(c.getColumnIndexOrThrow("sender_username")),
            c.getString(c.getColumnIndexOrThrow("content")),
            c.getString(c.getColumnIndexOrThrow("message_type")),
            String.valueOf(c.getLong(c.getColumnIndexOrThrow("timestamp"))),
            String.valueOf(c.getInt(c.getColumnIndexOrThrow("is_deleted")))
        };
    }
}
