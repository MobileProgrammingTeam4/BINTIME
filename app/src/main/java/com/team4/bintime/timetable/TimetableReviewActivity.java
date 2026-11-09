package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.ConfirmDialog;

import java.util.Collections;

public class TimetableReviewActivity extends AppCompatActivity {
    private static final String EDIT_EXISTING = "edit_existing_timetable";
    private static final String DELETE_COURSE = "delete_timetable_course";
    private TimetableDraft draft;
    private int pendingDelete = -1;
    private boolean editingExisting;

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
                TimetableDraft updated = TimetableDraft.read(result.getData());
                if (updated == null) return;
                if (editingExisting) MockTimetableRepository.save(updated);
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
        draft = editingExisting && state == null
                ? MockTimetableRepository.load() : TimetableDraft.restore(state, getIntent());
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
    }
}
