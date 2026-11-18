package com.team4.bintime.timetable;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.team4.bintime.R;
import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.TimetableRecognitionRepositories;
import com.team4.bintime.data.repository.TimetableImageFailure;
import com.team4.bintime.data.repository.TimetableImagePaths;
import com.team4.bintime.data.repository.TimetableImageRepositories;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시간표 이미지 선택·업로드. [BT-025] 선택하면 바로 원본을 검사해 문제 있는 이미지를 표시하고, 분석은
 * {@link TimetableUploadViewModel#analyze()} 한 경로로만 시작한다(오류 이미지가 있거나 검사 중이면 무시).
 * 압축 → 묶음 업로드가 끝나면 인식 결과 검수로 넘어간다. 화면은 상태 스냅샷({@link TimetableUploadScreen})만 그린다.
 * <p>
 * [BT-026] 업로드가 끝나면 인식(analyzeTimetable)을 부르고, 결과를 검수 화면으로 넘긴다. 인식이 실패하면 사유별 안내와
 * (가능하면) 다시 시도, 그리고 "직접 입력하기"로 수동 입력에 이어 간다. 여기서는 저장소에 쓰지 않는다.
 * P3: 업로드·인식 저장소는 모두 Mock이다(실제 연동은 BT-025 P2·BT-026 P4).
 * URI·파일명·이미지 내용을 로그에 남기지 않는다.
 */
public class TimetableUploadActivity extends AppCompatActivity {
    private static final int MAX_IMAGES = TimetableImagePaths.MAX_IMAGES;
    private static final String STATE_IMAGES = "timetable_images";

    private final ExecutorService thumbnails = Executors.newSingleThreadExecutor();
    private TimetableUploadViewModel upload;
    /** 지금 미리보기로 그려 둔 선택. ViewModel 선택과 다르면 미리보기를 다시 만든다. */
    private List<Uri> shown = new ArrayList<>();

    private final ActivityResultLauncher<PickVisualMediaRequest> gallery = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES), this::onImagesPicked);
    private final ActivityResultLauncher<Intent> review = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onReviewResult);
    /** [BT-026] 인식 실패 시 수동 입력. 저장까지 끝나면 같은 방식으로 결과를 넘긴다. */
    private final ActivityResultLauncher<Intent> manual = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::finishIfSaved);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_timetable_upload);
        upload = new ViewModelProvider(this).get(TimetableUploadViewModel.class);
        upload.attach(TimetableImageRepositories.get(this), TimetableRecognitionRepositories.get(this),
                getApplicationContext().getContentResolver());
        if (state != null) {
            // 회전이면 ViewModel에 선택이 남아 있다. 프로세스가 종료됐다가 돌아온 경우에만 다시 검사한다.
            upload.restoreIfEmpty(state.getParcelableArrayList(STATE_IMAGES));
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSelectFromGallery).setOnClickListener(v -> openGallery());
        // 분석 시작 경로는 이것 하나다. 판정은 ViewModel(TimetableUploadController.canAnalyze)이 한다.
        findViewById(R.id.btnAnalyze).setOnClickListener(v -> upload.analyze());
        findViewById(R.id.timetable_upload_manual).setOnClickListener(v -> openManualInput());
        upload.getScreen().observe(this, this::render);
    }

    /** [BT-026] 검수가 끝나면(저장이든 뒤로든) 인식에 쓴 이미지를 지운다(실패는 사용자에게 보이지 않음). */
    private void onReviewResult(ActivityResult result) {
        upload.onReviewFinished();
        finishIfSaved(result);
    }

    private void finishIfSaved(ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) {
            setResult(RESULT_OK, result.getData());
            finish();
        }
    }

    /** 인식 대신 직접 입력(새 시간표). 저장 전에 기존 수업 교체 확인을 받는다. */
    private void openManualInput() {
        if (currentScreen().isBusy()) return;
        manual.launch(TimetableDraft.open(this, CourseDirectInputActivity.class,
                new TimetableDraft(Collections.emptyList()).markReplacesExisting()));
    }

    private void openGallery() {
        TimetableUploadScreen screen = currentScreen();
        if (screen.upload.isBusy() || screen.recognizing) return;
        gallery.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
    }

    private TimetableUploadScreen currentScreen() {
        TimetableUploadScreen screen = upload.getScreen().getValue();
        return screen != null ? screen : TimetableUploadScreen.empty();
    }

    private void onImagesPicked(List<Uri> picked) {
        if (picked.isEmpty()) return;
        if (upload.select(picked)) {
            Toast.makeText(this, getString(R.string.timetable_upload_too_many, MAX_IMAGES),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void render(TimetableUploadScreen screen) {
        if (screen == null) return;
        List<Uri> selection = upload.selection();
        if (!selection.equals(shown)) showImages(selection);

        updateAnalyzeButton(screen);
        updateHint(screen, selection);

        ProgressBar progress = findViewById(R.id.timetable_upload_progress);
        boolean uploading = screen.upload.phase == TimetableUploadState.Phase.UPLOADING;
        progress.setVisibility(screen.upload.isBusy() || screen.recognizing ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!uploading);
        if (uploading) progress.setProgress(screen.upload.progressPercent);

        LinearLayout previews = findViewById(R.id.timetable_previews);
        for (int i = 0; i < previews.getChildCount(); i++) {
            View preview = previews.getChildAt(i);
            TimetableImageFailure.Code code = i < screen.imageFailures.size() ? screen.imageFailures.get(i) : null;
            preview.findViewById(R.id.vImageError).setVisibility(code != null ? View.VISIBLE : View.GONE);
            preview.findViewById(R.id.ivThumbnail).setContentDescription(code != null
                    ? getString(R.string.timetable_upload_image_error_cd, i + 1)
                    : "선택된 이미지 미리보기 " + (i + 1));
        }

        TextView errors = findViewById(R.id.timetable_upload_errors);
        String message = errorText(screen);
        errors.setText(message);
        errors.setVisibility(message != null ? View.VISIBLE : View.GONE);
        findViewById(R.id.timetable_upload_manual).setVisibility(
                RecognitionMessages.offersManualInput(screen.recognitionFailure) ? View.VISIBLE : View.GONE);

        openRecognitionIfReady();
    }

    private String errorText(TimetableUploadScreen screen) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < screen.imageFailures.size(); i++) {
            TimetableImageFailure.Code code = screen.imageFailures.get(i);
            if (code == null) continue;
            if (text.length() > 0) text.append('\n');
            text.append(getString(R.string.timetable_upload_image_error_line, i + 1,
                    getString(TimetableUploadMessages.of(code))));
        }
        TimetableUploadState state = screen.upload;
        if (text.length() == 0 && state.phase == TimetableUploadState.Phase.FAILED && state.failure != null) {
            text.append(getString(TimetableUploadMessages.of(state.failure.getCode())));
        }
        RecognitionFailure recognition = screen.recognitionFailure;
        if (text.length() == 0 && recognition != null) {
            text.append(getString(RecognitionMessages.of(recognition.getReason())));
        }
        return text.length() > 0 ? text.toString() : null;
    }

    /** 인식이 끝났으면 결과를 검수 화면으로 한 번만 넘긴다(중복 병합은 RecognitionDrafts). */
    private void openRecognitionIfReady() {
        if (isFinishing() || isDestroyed()) return;
        RecognitionResult result = upload.takeRecognition();
        if (result == null) return;
        review.launch(TimetableReviewActivity.forRecognition(this, RecognitionDrafts.toDraft(result),
                result.droppedCount, result.warnings));
    }

    private void showImages(List<Uri> selection) {
        shown = new ArrayList<>(selection);
        boolean selected = !selection.isEmpty();
        findViewById(R.id.layoutUploadBox).setBackgroundResource(selected
                ? R.drawable.bg_upload_card_selected : R.drawable.bg_upload_card);
        TextView title = findViewById(R.id.tvSelectHintTitle);
        TextView picker = findViewById(R.id.btnSelectFromGallery);
        picker.setText(selected ? getString(R.string.timetable_upload_reselect) : "사진 보관함에서 선택");

        LinearLayout previews = findViewById(R.id.timetable_previews);
        previews.removeAllViews();
        findViewById(R.id.timetable_preview_scroll).setVisibility(selected ? View.VISIBLE : View.GONE);
        title.setText("시간표 이미지 선택");
        if (!selected) return;
        thumbnails.execute(() -> {
            String filename = imageName(selection.get(0));
            if (selection.size() > 1) filename += " 외 " + (selection.size() - 1) + "장";
            String displayName = filename;
            runOnUiThread(() -> {
                if (isDestroyed() || !shown.equals(selection)) return;
                title.setText(displayName);
            });
        });
        for (int i = 0; i < selection.size(); i++) {
            Uri uri = selection.get(i);
            View preview = getLayoutInflater().inflate(R.layout.item_upload_image_preview, previews, false);
            ((TextView) preview.findViewById(R.id.tvOrderBadge)).setText(String.valueOf(i + 1));
            View remove = preview.findViewById(R.id.btnRemoveImage);
            remove.setContentDescription("이미지 " + (i + 1) + " 삭제");
            remove.setOnClickListener(v -> upload.remove(uri));
            previews.addView(preview);
            ImageView thumbnail = preview.findViewById(R.id.ivThumbnail);
            // 둥근 배경 모양대로 사진을 잘라 오류 테두리 밖으로 모서리가 삐져나오지 않게 한다.
            thumbnail.setClipToOutline(true);
            loadThumbnail(uri, thumbnail);
        }
    }

    /** 선택 장수와 검사 결과. 오류가 있으면 "분석 준비 완료"라고 쓰지 않는다. */
    private void updateHint(TimetableUploadScreen screen, List<Uri> selection) {
        TextView hint = findViewById(R.id.tvSelectHintSub);
        boolean selected = !selection.isEmpty();
        hint.setTextColor(ContextCompat.getColor(this, selected ? R.color.action_primary : R.color.text_tertiary));
        if (!selected) {
            hint.setText(getString(R.string.timetable_upload_hint_empty, MAX_IMAGES));
            return;
        }
        int status;
        if (screen.checking) {
            status = R.string.timetable_upload_status_checking;
        } else if (screen.hasImageFailures()) {
            status = R.string.timetable_upload_status_blocked;
        } else {
            status = R.string.timetable_upload_status_ready;
        }
        hint.setText(getString(R.string.timetable_upload_selected_count, selection.size(), getString(status)));
    }

    private void updateAnalyzeButton(TimetableUploadScreen screen) {
        TimetableUploadState state = screen.upload;
        boolean enabled = screen.canAnalyze;
        TextView analyze = findViewById(R.id.btnAnalyze);
        analyze.setEnabled(enabled);
        if (screen.checking || state.phase == TimetableUploadState.Phase.PREPARING) {
            analyze.setText(R.string.timetable_upload_preparing);
        } else if (state.phase == TimetableUploadState.Phase.UPLOADING) {
            analyze.setText(getString(R.string.timetable_upload_progress, state.progressPercent));
        } else if (screen.recognizing) {
            analyze.setText(R.string.timetable_upload_recognizing);
        } else if (state.canRetrySameSelection() || RecognitionMessages.showsRetry(screen.recognitionFailure)) {
            analyze.setText(R.string.timetable_upload_retry);
        } else {
            analyze.setText(R.string.timetable_upload_analyze);
        }
        analyze.setBackgroundResource(enabled ? R.drawable.bg_pill_primary : R.drawable.bg_btn_disabled_pill);
        analyze.setTextColor(ContextCompat.getColor(this, enabled ? R.color.bg_card : R.color.timetable_disabled_text));
        findViewById(R.id.btnSelectFromGallery).setEnabled(!state.isBusy() && !screen.recognizing);
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
        out.putParcelableArrayList(STATE_IMAGES, new ArrayList<>(upload.selection()));
    }

    @Override
    protected void onDestroy() {
        thumbnails.shutdownNow();
        super.onDestroy();
    }
}
