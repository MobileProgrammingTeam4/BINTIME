package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.recognition.RecognitionReview;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 인식 결과 검수 / 기존 시간표 수정. [BT-015] 기존 시간표 수정은 저장소에서 한 번 읽어 온 뒤에만
 * 편집·확인을 허용한다(읽기 전 빈 목록으로 저장해 수업을 지우지 않도록). 저장은 다음 화면
 * {@link TimetableRegistrationActivity}의 등록 완료에서 한다.
 * <p>
 * [BT-026] 이미지 인식 검수: 수업(시간대)마다 신뢰도·중복 병합·시간 겹침·수정 필요·주말을 표시하고
 * ({@link RecognitionReview}), 겹침이나 수정 필요가 남아 있거나 수업이 없으면 확인 버튼을 막는다. 수정·삭제할 때마다
 * 다시 판정한다. 검수 전에는 저장소에 쓰지 않는다.
 */
public class TimetableReviewActivity extends AppCompatActivity {
    private static final String EDIT_EXISTING = "edit_existing_timetable";
    private static final String DELETE_COURSE = "delete_timetable_course";
    private static final String LOADED = "timetable_review_loaded";
    private static final String EXTRA_DROPPED = "timetable_recognition_dropped";
    private static final String EXTRA_WARNINGS = "timetable_recognition_warnings";
    private static final String EXTRA_ORIGINALS = "timetable_recognition_originals";
    private TimetableDraft draft;
    private int pendingDelete = -1;
    private boolean editingExisting;
    private boolean loaded;
    private AsyncTimetableRepository.Registration loading;

    public static Intent forEditing(Context context) {
        return new Intent(context, TimetableReviewActivity.class).putExtra(EDIT_EXISTING, true);
    }

    /**
     * [BT-026] 인식 결과 검수. warnings 는 서버(모델) 안내 문장, dropped 는 형식이 틀려 뺀 수업 수.
     * [BT-048] originals 는 '원본 보기'용 앱 캐시 사본 경로(업로드 순서). 없으면 버튼을 숨긴다.
     */
    static Intent forRecognition(Context context, TimetableDraft draft, int dropped, List<String> warnings,
                                 List<String> originals) {
        return TimetableDraft.open(context, TimetableReviewActivity.class, draft)
                .putExtra(EXTRA_DROPPED, dropped)
                .putStringArrayListExtra(EXTRA_WARNINGS, new ArrayList<>(warnings))
                .putStringArrayListExtra(EXTRA_ORIGINALS,
                        originals == null ? new ArrayList<>() : new ArrayList<>(originals));
    }

    private final ActivityResultLauncher<Intent> editCourse = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                TimetableDraft updated = TimetableDraft.read(result.getData());
                if (result.getResultCode() == RESULT_OK && updated != null) {
                    draft = updated;
                    showCourses();
                }
            });

    private final ActivityResultLauncher<Intent> registration = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) return;
                if (TimetableDraft.read(result.getData()) == null) return;
                // 저장은 TimetableRegistrationActivity가 끝냈다.
                setResult(RESULT_OK, result.getData());
                finish();
            });

    private final ActivityResultLauncher<Intent> newTimetable = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) return;
                setResult(RESULT_OK);
                finish();
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_timetable_review);
        editingExisting = getIntent().getBooleanExtra(EDIT_EXISTING, false);
        loaded = !editingExisting || (state != null && state.getBoolean(LOADED, false));
        draft = loaded ? TimetableDraft.restore(state, getIntent()) : null;
        if (draft == null) {
            // 인식 결과 없이 열리면 빈 새 시간표(예시 수업으로 채우지 않는다).
            draft = editingExisting ? new TimetableDraft(Collections.emptyList())
                    : new TimetableDraft(Collections.emptyList()).markReplacesExisting();
        }
        if (state != null) pendingDelete = state.getInt(DELETE_COURSE, -1);
        if (editingExisting) {
            ((TextView) findViewById(R.id.timetable_review_title)).setText(R.string.timetable_edit_title);
            findViewById(R.id.timetable_analysis_summary).setVisibility(View.GONE);
            findViewById(R.id.timetable_analysis_footer_note).setVisibility(View.GONE);
            TextView confirm = findViewById(R.id.btnConfirm);
            confirm.setText(R.string.timetable_register_current);
            confirm.setTextAppearance(R.style.TextAppearance_BinTime_ButtonS);
            confirm.setTextColor(getColor(R.color.bg_card));
            View registerNew = findViewById(R.id.timetable_register_new);
            registerNew.setVisibility(View.VISIBLE);
            registerNew.setOnClickListener(v -> newTimetable.launch(new Intent(this, TimetableSelectModeActivity.class)));
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        bindOriginals();
        findViewById(R.id.timetable_add_course).setOnClickListener(v ->
                editCourse.launch(CourseModifyActivity.open(this, draft, -1)));
        findViewById(R.id.btnConfirm).setOnClickListener(v ->
                registration.launch(TimetableDraft.open(this, TimetableRegistrationActivity.class, draft)));
        getSupportFragmentManager().setFragmentResultListener(DELETE_COURSE, this, (key, result) -> {
            if (pendingDelete >= 0 && pendingDelete < draft.courses.size()) {
                draft.courses.remove(pendingDelete);
                showCourses();
            }
            pendingDelete = -1;
        });
        showCourses();
        setEditable(loaded);
        if (!loaded) loadExisting();
    }

    /** [BT-048] 원본 사본이 아직 있으면(프로세스 재시작 뒤 지워졌을 수 있다) '원본 보기'를 보인다. */
    private void bindOriginals() {
        View button = findViewById(R.id.timetable_view_original);
        boolean available = reviewingRecognition()
                && !RecognitionImageCache.existing(getIntent().getStringArrayListExtra(EXTRA_ORIGINALS)).isEmpty();
        button.setVisibility(available ? View.VISIBLE : View.GONE);
        button.setOnClickListener(v -> {
            ArrayList<String> found = new ArrayList<>(
                    RecognitionImageCache.existing(getIntent().getStringArrayListExtra(EXTRA_ORIGINALS)));
            if (found.isEmpty()) {
                button.setVisibility(View.GONE);
                Toast.makeText(this, R.string.timetable_original_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            OriginalImagesDialog.show(getSupportFragmentManager(), found);
        });
    }

    private boolean reviewingRecognition() {
        return !editingExisting;
    }

    private void loadExisting() {
        loading = TimetableLoader.loadOnce(TimetableRepositories.get(this),
                new AsyncTimetableRepository.Callback<Timetable>() {
                    @Override
                    public void onSuccess(Timetable timetable) {
                        if (isFinishing() || isDestroyed()) return;
                        draft = TimetableDraftMapper.toDraft(timetable);
                        loaded = true;
                        showCourses();
                        setEditable(true);
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(TimetableReviewActivity.this,
                                failure.getCode() == TimetableFailure.Code.NOT_SIGNED_IN
                                        ? TimetableMessages.of(failure) : TimetableMessages.LOAD_FAILED,
                                Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
    }

    /** 기존 시간표를 읽기 전에는 추가·수정·삭제·확인을 막는다. */
    private void setEditable(boolean editable) {
        for (int id : new int[]{R.id.timetable_add_course, R.id.timetable_register_new}) {
            View v = findViewById(id);
            v.setEnabled(editable);
            v.setAlpha(editable ? 1f : 0.5f);
        }
        updateConfirm(editable, reviewingRecognition() ? RecognitionDrafts.check(draft) : null);
        LinearLayout courseList = findViewById(R.id.layoutLectureList);
        courseList.setVisibility(editable ? View.VISIBLE : View.INVISIBLE);
        if (!editable) findViewById(R.id.timetable_recognition_empty).setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        if (loading != null) loading.remove();
        super.onDestroy();
    }

    private void showCourses() {
        LinearLayout courseList = findViewById(R.id.layoutLectureList);
        courseList.removeAllViews();
        List<Set<RecognitionReview.Flag>> flags = reviewingRecognition() ? RecognitionDrafts.check(draft) : null;
        for (int i = 0; i < draft.courses.size(); i++) {
            int index = i;
            Course course = draft.courses.get(i);
            View card = getLayoutInflater().inflate(R.layout.item_lecture_review, courseList, false);
            ((TextView) card.findViewById(R.id.tvCourseTitle)).setText(course.getName());
            ((TextView) card.findViewById(R.id.tvCourseInfo)).setText(course.getFormattedInfo());
            if (flags != null) bindFlags(card.findViewById(R.id.tvCourseFlags), course, flags.get(i));
            View edit = card.findViewById(R.id.btnEditLecture);
            edit.setContentDescription(course.getName() + " 수정");
            edit.setOnClickListener(v -> editCourse.launch(CourseModifyActivity.open(this, draft, index)));
            View delete = card.findViewById(R.id.btnDeleteLecture);
            delete.setContentDescription(course.getName() + " 삭제");
            delete.setOnClickListener(v -> {
                pendingDelete = index;
                ConfirmDialog.show(getSupportFragmentManager(), DELETE_COURSE,
                        "해당 과목을 삭제할까요", "선택한 과목을 삭제할게요", "삭제");
            });
            courseList.addView(card);
        }
        if (flags != null) showRecognitionSummary(flags);
        findViewById(R.id.timetable_recognition_empty).setVisibility(
                draft.courses.isEmpty() ? View.VISIBLE : View.GONE);
        updateConfirm(loaded, flags);
    }

    /** 확인 버튼: 읽기 전이면 막고, 인식 검수 중이면 겹침·수정 필요가 없고 수업이 1개 이상일 때만 연다. */
    private void updateConfirm(boolean editable, List<Set<RecognitionReview.Flag>> flags) {
        boolean enabled = editable && (flags == null || RecognitionReview.canConfirm(flags));
        View confirm = findViewById(R.id.btnConfirm);
        confirm.setEnabled(enabled);
        confirm.setAlpha(enabled ? 1f : 0.5f);
    }

    /** 행 표시: 막는 판정(빨강)을 먼저, 안내(회색)를 뒤에. */
    private void bindFlags(TextView view, Course course, Set<RecognitionReview.Flag> flags) {
        List<String> lines = new ArrayList<>();
        boolean blocking = false;
        for (RecognitionReview.Flag flag : flags) {
            if (!flag.blocking) continue;
            blocking = true;
            lines.add(getString(RecognitionMessages.flag(flag)));
        }
        for (RecognitionReview.Flag flag : flags) {
            if (flag.blocking) continue;
            lines.add(flag == RecognitionReview.Flag.LOW_CONFIDENCE
                    ? getString(RecognitionMessages.flag(flag),
                    (int) Math.round(course.getRecognitionConfidence() * 100))
                    : getString(RecognitionMessages.flag(flag)));
        }
        view.setText(android.text.TextUtils.join(" · ", lines));
        view.setTextColor(getColor(blocking ? R.color.action_primary : R.color.text_secondary));
        view.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showRecognitionSummary(List<Set<RecognitionReview.Flag>> flags) {
        int attention = 0;
        for (Set<RecognitionReview.Flag> f : flags) {
            for (RecognitionReview.Flag flag : f) {
                if (flag.blocking || flag == RecognitionReview.Flag.LOW_CONFIDENCE) {
                    attention++;
                    break;
                }
            }
        }
        ((TextView) findViewById(R.id.tvCourseCountBadge)).setText(
                getString(R.string.timetable_review_summary, draft.courses.size(), attention));
        StringBuilder desc = new StringBuilder();
        int blocking = RecognitionReview.blockingCount(flags);
        if (draft.courses.isEmpty()) {
            desc.append(getString(R.string.timetable_review_empty_recognition));
        } else if (blocking > 0) {
            desc.append(getString(R.string.timetable_review_blocked, blocking));
        } else {
            desc.append(getString(R.string.timetable_review_ready));
        }
        int dropped = getIntent().getIntExtra(EXTRA_DROPPED, 0);
        if (dropped > 0) desc.append('\n').append(getString(R.string.timetable_review_dropped, dropped));
        ArrayList<String> warnings = getIntent().getStringArrayListExtra(EXTRA_WARNINGS);
        if (warnings != null) for (String w : warnings) desc.append('\n').append(w);
        TextView descView = findViewById(R.id.tvAnalysisDesc);
        descView.setText(desc.toString());
        descView.setTextColor(getColor(blocking > 0 || draft.courses.isEmpty()
                ? R.color.action_primary : R.color.text_secondary));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        draft.save(out);
        out.putInt(DELETE_COURSE, pendingDelete);
        out.putBoolean(LOADED, loaded);
    }
}
