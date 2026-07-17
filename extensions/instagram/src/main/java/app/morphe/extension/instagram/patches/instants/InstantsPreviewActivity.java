/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.io.InputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

import static app.morphe.extension.instagram.utils.IgStr.str;

/**
 * Preview screen for the Instants gallery post flow. Shows the picked image with "Post to Instant"
 * and "Cancel" buttons; "Post" hands the bitmap to InstantsHook.stageAndPost(). Launched by
 * InstantsGalleryPickerActivity.
 */
public class InstantsPreviewActivity extends Activity {

    private Bitmap picked;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri imageUri = getIntent().getData();
        if (imageUri == null) {
            finish();
            return;
        }

        // Full-screen image with a button bar at the bottom.
        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        // Image preview — fills most of the screen
        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);

        // Decode once and reuse for both the preview and the post.
        try (InputStream in = getContentResolver().openInputStream(imageUri)) {
            picked = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Logger.printException(() -> "instants preview decode failed", e);
        }
        if (picked == null) {
            finish();
            return;
        }
        imageView.setImageBitmap(picked);

        root.addView(imageView);

        // Button bar at the bottom
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp24);
        int fg = InstantsHook.themed("igds_color_primary_button_icon", 0xFFFFFFFF);

        // Cancel button
        Button cancelBtn = new Button(this);
        cancelBtn.setText(str("piko_cancel"));
        cancelBtn.setAllCaps(false);
        cancelBtn.setTextColor(fg);
        cancelBtn.setBackgroundColor(0x33FFFFFF);
        cancelBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelParams.setMargins(0, 0, Dim.dp8, 0);
        cancelBtn.setLayoutParams(cancelParams);
        buttonBar.addView(cancelBtn);

        // Post to Instant button
        Button postBtn = new Button(this);
        postBtn.setText(str("piko_instants_post"));
        postBtn.setAllCaps(false);
        postBtn.setTextColor(fg);
        postBtn.setBackgroundColor(InstantsHook.themed("igds_color_primary_button", 0xFF0095F6));
        postBtn.setOnClickListener(v -> {
            try {
                boolean posted = InstantsHook.stageAndPost(this, picked);
                Utils.showToastShort(str(posted
                        ? "piko_instants_posting"
                        : "piko_instants_post_failed"));
            } catch (Exception e) {
                Logger.printException(() -> "instants post failed", e);
                Utils.showToastShort(str("piko_instants_post_failed"));
            }
            finish();
        });
        LinearLayout.LayoutParams postParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        postParams.setMargins(Dim.dp8, 0, 0, 0);
        postBtn.setLayoutParams(postParams);
        buttonBar.addView(postBtn);

        root.addView(buttonBar);
        setContentView(root);
    }
}
