/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Picker Activity for "Instants gallery post": picks an image, then launches
 * InstantsPreviewActivity to preview and post it.
 */
public class InstantsGalleryPickerActivity extends Activity {

    private static final int IMAGE_REQUEST_CODE = 4342;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, IMAGE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == IMAGE_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                // Preview the picked image, then post it to an Instant on confirm.
                Intent previewIntent = new Intent(this, InstantsPreviewActivity.class);
                previewIntent.setData(imageUri);
                previewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(previewIntent);
            }
        }
        finish();
    }
}
