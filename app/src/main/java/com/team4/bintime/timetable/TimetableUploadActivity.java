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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.team4.bintime.R;
import com.team4.bintime.data.repository.TimetableImageFailure;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepositories;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시간표 이미지 선택·업로드. [BT-025] 이미지 검사·압축 → 묶음 업로드({@link TimetableUploadViewModel})가 끝나면
 * 인식 결과 검수로 넘어간다.
 * <p>
 * P1: 업로드 저장소는 Mock이고 인식은 아직 고정 예시({@link TimetableDraft#mockRecognition()})다. 실제 인식
 * (analyzeTimetable)은 BT-026에서 {@link TimetableUploadViewModel#uploadedPaths()}로 연결한다.
 * URI·파일명·이미지 내용을 로그에 남기지 않는다.
 */
public class TimetableUploadActivity extends AppCompatActivity {
    private static final int MAX_IMAGES = TimetableImagePaths.MAX_IMAGES;
    private static final String STATE_IMAGES = "timetable_images";

    private final ArrayList<Uri> images = new ArrayList<>();
    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private TimetableUploadViewModel upload;

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
        upload = new ViewModelProvider(this).get(TimetableUploadViewModel.class);
        upload.attach(TimetableImageRepositories.get(this));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSelectFromGallery).setOnClickListener(v -> openGallery());
        findViewById(R.id.btnAnalyze).setOnClickListener(v -> analyzeImages());
        showImages();
        upload.getState().observe(this, this::render);
    }

    private void openGallery() {
        if (currentState().isBusy()) return;
        gallery.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private void analyzeImages() {
        TimetableUploadState state = currentState();
        if (state.isBusy() || images.isEmpty()) return;
        if (state.phase == TimetableUploadState.Phase.UPLOADED) {
            // 같은 선택은 다시 올리지 않고 인식 단계로 넘어간다.
            upload.requestRecognitionAgain();
            openRecognitionIfReady();
            return;
        }
        upload.start(getApplicationContext().getContentResolver(), images);
    }

    private TimetableUploadState currentState() {
        TimetableUploadState state = upload.getState().getValue();
        return state != null ? state : TimetableUploadState.idle();
    }

    private void render(TimetableUploadState state) {
        if (state == null) return;
        updateAnalyzeButton(state);

        ProgressBar progress = findViewById(R.id.timetable_upload_progress);
        boolean uploading = state.phase == TimetableUploadState.Phase.UPLOADING;
        progress.setVisibility(state.isBusy() ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!uploading);
        if (uploading) progress.setProgress(state.progressPercent);

        boolean matchesSelection = state.imageCount == images.size();
        LinearLayout previews = findViewById(R.id.timetable_previews);
        for (int i = 0; i < previews.getChildCount(); i++) {
            View preview = previews.getChildAt(i);
            TimetableImageFailure.Code code = matchesSelection && i < state.imageFailures.size()
                    ? state.imageFailures.get(i) : null;
            preview.findViewById(R.id.vImageError).setVisibility(code != null ? View.VISIBLE : View.GONE);
            preview.findViewById(R.id.ivThumbnail).setContentDescription(code != null
                    ? getString(R.string.timetable_upload_image_error_cd, i + 1)
                    : "선택된 이미지 미리보기 " + (i + 1));
        }

        TextView errors = findViewById(R.id.timetable_upload_errors);
        String message = state.phase == TimetableUploadState.Phase.FAILED ? errorText(state, matchesSelection) : null;
        errors.setText(message);
        errors.setVisibility(message != null ? View.VISIBLE : View.GONE);

        openRecognitionIfReady();
    }

    private String errorText(TimetableUploadState state, boolean matchesSelection) {
        StringBuilder text = new StringBuilder();
        if (matchesSelection) {
            for (int i = 0; i < state.imageFailures.size(); i++) {
                TimetableImageFailure.Code code = state.imageFailures.get(i);
                if (code == null) continue;
                if (text.length() > 0) text.append('\n');
                text.append(getString(R.string.timetable_upload_image_error_line, i + 1,
                        getString(TimetableUploadMessages.of(code))));
            }
        }
        if (text.length() == 0 && state.failure != null) {
            text.append(getString(TimetableUploadMessages.of(state.failure.getCode())));
        }
        return text.length() > 0 ? text.toString() : null;
    }

    /** 업로드가 끝났으면 인식 결과 검수로 한 번만 넘어간다. P1은 고정 예시 인식. */
    private void openRecognitionIfReady() {
        if (isFinishing() || isDestroyed() || !upload.takeRecognitionRequest()) return;
        review.launch(TimetableDraft.open(this,
                TimetableReviewActivity.class, TimetableDraft.mockRecognition()));
    }

    private void onImagesPicked(List<Uri> picked) {
        if (picked.isEmpty()) return;
        ArrayList<Uri> unique = new ArrayList<>(new LinkedHashSet<>(picked));
        if (unique.size() > MAX_IMAGES) {
            Toast.makeText(this, getString(R.string.timetable_upload_too_many, MAX_IMAGES),
                    Toast.LENGTH_LONG).show();
        }
        upload.discard();
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
        picker.setText(selected ? getString(R.string.timetable_upload_reselect) : "사진 보관함에서 선택");
        updateAnalyzeButton(currentState());
        hint.setTextColor(ContextCompat.getColor(this, selected ? R.color.action_primary : R.color.text_tertiary));

        LinearLayout previews = findViewById(R.id.timetable_previews);
        previews.removeAllViews();
        findViewById(R.id.timetable_preview_scroll).setVisibility(selected ? View.VISIBLE : View.GONE);
        title.setText("시간표 이미지 선택");
        if (!selected) {
            hint.setText(getString(R.string.timetable_upload_hint_empty, MAX_IMAGES));
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
                if (currentState().isBusy()) return;
                upload.discard();
                images.remove(uri);
                showImages();
            });
            previews.addView(preview);
            loadThumbnail(uri, preview.findViewById(R.id.ivThumbnail));
        }
        render(currentState());
    }

    private void updateAnalyzeButton(TimetableUploadState state) {
        boolean busy = state.isBusy();
        boolean blockedByImages = state.phase == TimetableUploadState.Phase.FAILED && state.hasImageFailures();
        boolean enabled = !images.isEmpty() && !busy && !blockedByImages;
        TextView analyze = findViewById(R.id.btnAnalyze);
        analyze.setEnabled(enabled);
        if (state.phase == TimetableUploadState.Phase.PREPARING) {
            analyze.setText(R.string.timetable_upload_preparing);
        } else if (state.phase == TimetableUploadState.Phase.UPLOADING) {
            analyze.setText(getString(R.string.timetable_upload_progress, state.progressPercent));
        } else if (state.canRetrySameSelection()) {
            analyze.setText(R.string.timetable_upload_retry);
        } else {
            analyze.setText(R.string.timetable_upload_analyze);
        }
        analyze.setBackgroundResource(enabled ? R.drawable.bg_pill_primary : R.drawable.bg_btn_disabled_pill);
        analyze.setTextColor(ContextCompat.getColor(this, enabled ? R.color.bg_card : R.color.timetable_disabled_text));
        findViewById(R.id.btnSelectFromGallery).setEnabled(!busy);
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
    protected void onDestroy() {
        thumbnails.shutdownNow();
        super.onDestroy();
    }
}
