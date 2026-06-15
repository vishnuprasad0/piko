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
import android.view.WindowInsets;
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

    private ListView listView;
    private List<String[]> messages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        messages = PikoMessageDb.getInstance(this).getDeletedMessages();

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
        title.setText("Deleted Messages");
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

        // Message list
        listView = new ListView(this);

        if (messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No deleted messages captured yet.\nMessages are saved as they arrive and marked when deleted.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Dim.dp8 * 2, Dim.dp8 * 4, Dim.dp8 * 2, Dim.dp8 * 4);
            empty.setTextColor(UI.getThemedColour());
            root.addView(toolbar);
            root.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ));
        } else {
            listView.setAdapter(new MessageAdapter());
            root.addView(toolbar);
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

        @Override
        public int getCount() { return messages.size(); }

        @Override
        public Object getItem(int position) { return messages.get(position); }

        @Override
        public long getItemId(int position) { return position; }

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
                senderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                senderView.setTextColor(UI.getThemedColour());
                senderView.setTag("sender");

                contentView = new TextView(DeletedMessagesActivity.this);
                contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                contentView.setTag("content");

                metaView = new TextView(DeletedMessagesActivity.this);
                metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                metaView.setTag("meta");

                row.addView(senderView);
                row.addView(contentView);
                row.addView(metaView);
            } else {
                row = (LinearLayout) convertView;
                senderView = row.findViewWithTag("sender");
                contentView = row.findViewWithTag("content");
                metaView = row.findViewWithTag("meta");
            }

            // [messageId, threadId, senderUsername, content, messageType, timestamp]
            String[] msg = messages.get(position);
            String sender = msg[2];
            String content = msg[3];
            String type = msg[4];
            long timestamp = 0;
            try { timestamp = Long.parseLong(msg[5]); } catch (Exception ignored) {}

            senderView.setText(sender != null && !sender.isEmpty() ? sender : "Unknown");
            contentView.setText(content != null && !content.isEmpty() ? content : "[" + type + "]");
            metaView.setText(
                DateFormat.format("MMM dd, yyyy HH:mm", new Date(timestamp)).toString()
                + "  •  Thread: " + (msg[1] != null ? msg[1].substring(0, Math.min(8, msg[1].length())) + "…" : "?")
            );

            return row;
        }
    }
}
