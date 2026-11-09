package com.team4.bintime.timetable;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.common.ConfirmDialog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TimetableUploadActivity extends AppCompatActivity {
    private static final int MAX_IMAGES = 10;
    private static final String STATE_IMAGES = "timetable_images";
    private static final String RETRY_RECOGNITION = "retry_timetable_recognition";

    private final ArrayList<Uri> images = new ArrayList<>();
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private boolean analyzing;
    private boolean resumed;
    private Boolean pendingRecognition;

    private final ActivityResultLauncher<PickVisualMediaRequest> gallery = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES), this::onImagesPicked);
    private final ActivityResultLauncher<Intent> review = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK, result.getData());
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_timetable_upload);
        if (state != null) {
            ArrayList<Uri> restored = state.getParcelableArrayList(STATE_IMAGES);
            if (restored != null) images.addAll(restored);
        }
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSelectFromGallery).setOnClickListener(v -> openGallery());
        findViewById(R.id.btnAnalyze).setOnClickListener(v -> analyzeImages());
        getSupportFragmentManager().setFragmentResultListener(RETRY_RECOGNITION, this,
                (key, result) -> openGallery());
        showImages();
    }

    private void openGallery() {
        gallery.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private void analyzeImages() {
        if (analyzing || images.isEmpty()) return;
        analyzing = true;
        updateAnalyzeButton();
        List<Uri> selection = new ArrayList<>(images);
        thumbnails.execute(() -> {
            boolean readable = selection.stream().allMatch(this::canDecodeImage);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                pendingRecognition = readable;
                showRecognitionResult();
            });
        });
    }

    private void showRecognitionResult() {
        if (pendingRecognition == null || !resumed || getSupportFragmentManager().isStateSaved()) return;
        boolean readable = pendingRecognition;
        pendingRecognition = null;
        analyzing = false;
        updateAnalyzeButton();
        if (readable) {
            review.launch(TimetableDraft.open(this,
                    TimetableReviewActivity.class, TimetableDraft.mockRecognition()));
        } else {
            ConfirmDialog.show(getSupportFragmentManager(), RETRY_RECOGNITION,
                    "시간표 이미지를 인식하지 못했어요",
                    "시간표 전체가 선명하게 보이는 이미지를 다시 선택해 주세요.",
                    "다시 선택", "닫기");
        }
    }

    private boolean canDecodeImage(Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 16;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) return false;
            bitmap.recycle();
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private void onImagesPicked(List<Uri> picked) {
        if (picked.isEmpty()) return;
        ArrayList<Uri> unique = new ArrayList<>(new LinkedHashSet<>(picked));
        images.clear();
        images.addAll(unique.subList(0, Math.min(unique.size(), MAX_IMAGES)));
        showImages();
    }

    private void showImages() {
        boolean selected = !images.isEmpty();
        findViewById(R.id.layoutUploadBox).setBackgroundResource(selected
                ? R.drawable.bg_upload_card_selected : R.drawable.bg_upload_card);
        TextView title = findViewById(R.id.tvSelectHintTitle);
        TextView hint = findViewById(R.id.tvSelectHintSub);
        TextView picker = findViewById(R.id.btnSelectFromGallery);
        picker.setText(selected ? "이미지 다시 선택" : "사진 보관함에서 선택");
        updateAnalyzeButton();
        hint.setTextColor(ContextCompat.getColor(this, selected ? R.color.action_primary : R.color.text_tertiary));

        LinearLayout previews = findViewById(R.id.timetable_previews);
        previews.removeAllViews();
        findViewById(R.id.timetable_preview_scroll).setVisibility(selected ? View.VISIBLE : View.GONE);
        title.setText("시간표 이미지 선택");
        if (!selected) {
            hint.setText("JPG · PNG · 이미지 여러 장");
            return;
        }
        String count = "이미지 " + images.size() + "장 선택됨";
        hint.setText(count + " · AI 분석 준비 완료");
        List<Uri> selection = new ArrayList<>(images);
        thumbnails.execute(() -> {
            String filename = imageName(selection.get(0));
            if (selection.size() > 1) filename += " 외 " + (selection.size() - 1) + "장";
            long bytes = 0;
            for (Uri uri : selection) bytes += imageBytes(uri);
            String size = bytes > 0 ? " · " + Formatter.formatShortFileSize(this, bytes) : "";
            String displayName = filename;
            runOnUiThread(() -> {
                if (isDestroyed() || !images.equals(selection)) return;
                title.setText(displayName);
                hint.setText(count + size + " · AI 분석 준비 완료");
            });
        });
        for (int i = 0; i < images.size(); i++) {
            Uri uri = images.get(i);
            View preview = getLayoutInflater().inflate(R.layout.item_upload_image_preview, previews, false);
            ((TextView) preview.findViewById(R.id.tvOrderBadge)).setText(String.valueOf(i + 1));
            View remove = preview.findViewById(R.id.btnRemoveImage);
            remove.setContentDescription("이미지 " + (i + 1) + " 삭제");
            remove.setOnClickListener(v -> {
                if (analyzing) return;
                images.remove(uri);
                showImages();
            });
            previews.addView(preview);
            loadThumbnail(uri, preview.findViewById(R.id.ivThumbnail));
        }
    }

    private void updateAnalyzeButton() {
        boolean enabled = !images.isEmpty() && !analyzing;
        TextView analyze = findViewById(R.id.btnAnalyze);
        analyze.setEnabled(enabled);
        analyze.setText(analyzing ? "시간표 분석 중…" : "시간표 분석하기");
        analyze.setBackgroundResource(enabled ? R.drawable.bg_pill_primary : R.drawable.bg_btn_disabled_pill);
        analyze.setTextColor(ContextCompat.getColor(this, enabled ? R.color.bg_card : R.color.timetable_disabled_text));
        findViewById(R.id.btnSelectFromGallery).setEnabled(!analyzing);
    }

    private String imageName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // A provider may omit metadata even when it can supply the image.
        }
        return "시간표 이미지";
    }

    private long imageBytes(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return Math.max(0, cursor.getLong(0));
        } catch (RuntimeException ignored) {
            // The size is optional.
        }
        return 0;
    }

    private void loadThumbnail(Uri uri, ImageView target) {
        thumbnails.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    bitmap = getContentResolver().loadThumbnail(uri, new android.util.Size(216, 216), null);
                } else {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    try (InputStream stream = getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(stream, null, options);
                    }
                    options.inSampleSize = 1;
                    while (Math.max(options.outHeight, options.outWidth) / options.inSampleSize > 432) {
                        options.inSampleSize *= 2;
                    }
                    options.inJustDecodeBounds = false;
                    try (InputStream stream = getContentResolver().openInputStream(uri)) {
                        bitmap = BitmapFactory.decodeStream(stream, null, options);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the image placeholder if the provider cannot load its thumbnail.
            }
            Bitmap preview = bitmap;
            if (preview != null) target.post(() -> {
                if (target.isAttachedToWindow()) target.setImageBitmap(preview);
            });
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList(STATE_IMAGES, images);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        resumed = true;
        showRecognitionResult();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        thumbnails.shutdownNow();
        super.onDestroy();
    }
}
