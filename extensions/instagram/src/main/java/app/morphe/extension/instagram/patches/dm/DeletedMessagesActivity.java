/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.dm;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.db.PikoMessageDb;
import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.ui.Dim;

public class DeletedMessagesActivity extends Activity {

    private List<String[]> messages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // When launched from compose-bar button, filter to that thread only.
        // When launched from Piko settings, show all deleted messages.
        String threadId = getIntent().getStringExtra("thread_id");
        messages = threadId != null
            ? PikoMessageDb.getInstance(this).getDeletedMessagesForThread(threadId)
            : PikoMessageDb.getInstance(this).getDeletedMessages();

        String titleText = threadId != null ? "Deleted in this chat" : "All deleted messages";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Toolbar
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);

        ImageView back = new ImageView(this);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
        backParams.gravity = Gravity.CENTER_VERTICAL;
        back.setLayoutParams(backParams);
        UI.setThemedIcon(back, "material_ic_keyboard_arrow_left_black_24dp");
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, PikoUtils.spToPixels(20));
        title.setTextColor(UI.getThemedColour());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.gravity = Gravity.CENTER_VERTICAL;
        titleParams.leftMargin = Dim.dp8 / 2;
        title.setLayoutParams(titleParams);

        toolbar.addView(back);
        toolbar.addView(title);
        root.addView(toolbar);

        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No deleted messages captured yet.\nEnable the feature and messages will be saved as they arrive.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Dim.dp8 * 2, Dim.dp8 * 4, Dim.dp8 * 2, Dim.dp8 * 4);
            empty.setTextColor(UI.getThemedColour());
            root.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ));
        } else {
            ListView listView = new ListView(this);
            listView.setAdapter(new MessageAdapter());
            listView.setDividerHeight(1);
            root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ));
        }

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });

        setContentView(root);
    }

    private class MessageAdapter extends BaseAdapter {

        @Override public int getCount() { return messages.size(); }
        @Override public Object getItem(int pos) { return messages.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView senderView, contentView, metaView;

            if (convertView == null) {
                row = new LinearLayout(DeletedMessagesActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = Dim.dp8;
                row.setPadding(pad * 2, pad, pad * 2, pad);

                senderView = new TextView(DeletedMessagesActivity.this);
                senderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                senderView.setTextColor(UI.getThemedColour());
                senderView.setTag("s");

                contentView = new TextView(DeletedMessagesActivity.this);
                contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                contentView.setTag("c");

                metaView = new TextView(DeletedMessagesActivity.this);
                metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                metaView.setTag("m");

                row.addView(senderView);
                row.addView(contentView);
                row.addView(metaView);
            } else {
                row = (LinearLayout) convertView;
                senderView = row.findViewWithTag("s");
                contentView = row.findViewWithTag("c");
                metaView    = row.findViewWithTag("m");
            }

            // [messageId, threadId, senderUsername, content, messageType, timestamp, senderId]
            String[] msg = messages.get(position);
            String sender    = msg[2];
            String content   = msg[3];
            String type      = msg[4];
            String senderId  = msg.length > 6 ? msg[6] : null;
            long   timestamp = 0;
            try { timestamp = Long.parseLong(msg[5]); } catch (Exception ignored) {}

            // Prefer the resolved username; fall back to the numeric sender id so the row is
            // still attributable instead of an opaque "Unknown".
            final String who;
            if (sender != null && !sender.isEmpty()) {
                who = "@" + sender;
            } else if (senderId != null && !senderId.isEmpty()) {
                who = "@" + senderId;
            } else {
                who = "Unknown";
            }
            senderView.setText(who);
            contentView.setText(content != null && !content.isEmpty() ? content : "[" + type + "]");
            metaView.setText(DateFormat.format("MMM dd, yyyy  HH:mm", new Date(timestamp)));

            return row;
        }
    }
}
