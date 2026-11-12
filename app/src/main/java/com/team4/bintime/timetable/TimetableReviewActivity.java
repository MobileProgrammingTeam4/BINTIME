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
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.Collections;

/**
 * 인식 결과 검수 / 기존 시간표 수정. [BT-015] 기존 시간표 수정은 저장소에서 한 번 읽어 온 뒤에만
 * 편집·확인을 허용한다(읽기 전 빈 목록으로 저장해 수업을 지우지 않도록). 저장은 다음 화면
 * {@link TimetableRegistrationActivity}의 등록 완료에서 한다.
 */
public class TimetableReviewActivity extends AppCompatActivity {
    private static final String EDIT_EXISTING = "edit_existing_timetable";
    private static final String DELETE_COURSE = "delete_timetable_course";
    private static final String LOADED = "timetable_review_loaded";
    private TimetableDraft draft;
    private int pendingDelete = -1;
    private boolean editingExisting;
    private boolean loaded;
    private AsyncTimetableRepository.Registration loading;

    public static Intent forEditing(Context context) {
        return new Intent(context, TimetableReviewActivity.class).putExtra(EDIT_EXISTING, true);
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
            draft = editingExisting ? new TimetableDraft(Collections.emptyList())
                    : TimetableDraft.mockRecognition();
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
        for (int id : new int[]{R.id.timetable_add_course, R.id.btnConfirm, R.id.timetable_register_new}) {
            View v = findViewById(id);
            v.setEnabled(editable);
            v.setAlpha(editable ? 1f : 0.5f);
        }
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
        for (int i = 0; i < draft.courses.size(); i++) {
            int index = i;
            Course course = draft.courses.get(i);
            View card = getLayoutInflater().inflate(R.layout.item_lecture_review, courseList, false);
            ((TextView) card.findViewById(R.id.tvCourseTitle)).setText(course.getName());
            ((TextView) card.findViewById(R.id.tvCourseInfo)).setText(course.getFormattedInfo());
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
        if (!editingExisting) {
            TextView count = findViewById(R.id.tvCourseCountBadge);
            count.setText("2026년 2학기 · 수업 " + draft.courses.size() + "개");
        }
        findViewById(R.id.timetable_recognition_empty).setVisibility(
                draft.courses.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        draft.save(out);
        out.putInt(DELETE_COURSE, pendingDelete);
        out.putBoolean(LOADED, loaded);
    }
}
