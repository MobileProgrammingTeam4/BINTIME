package com.team4.bintime.timetable;

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

public class CourseDirectInputActivity extends AppCompatActivity {
    private static final String DELETE_COURSE = "delete_timetable_course";
    private TimetableDraft draft;
    private CourseFields fields;
    private LinearLayout courseList;
    private TextView save;
    private int pendingDelete = -1;

    private final ActivityResultLauncher<Intent> editCourse = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                TimetableDraft updated = TimetableDraft.read(result.getData());
                if (result.getResultCode() == RESULT_OK && updated != null) {
                    draft = updated;
                    showCourses();
                }
            });

    private final ActivityResultLauncher<Intent> register = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK, result.getData());
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_course_direct_input);
        draft = TimetableDraft.restore(state, getIntent());
        if (draft == null) draft = new TimetableDraft(Collections.emptyList());
        if (state != null) pendingDelete = state.getInt(DELETE_COURSE, -1);
        fields = new CourseFields(this, CourseFields.restore(state, CourseFields.empty()), null);
        courseList = findViewById(R.id.layoutCourseList);
        save = findViewById(R.id.btnSaveAndRegister);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddCourseAndContinue).setOnClickListener(v -> addCourse());
        save.setOnClickListener(v -> {
            if (fields.hasInput() && !addCourse()) return;
            register.launch(TimetableDraft.open(this, TimetableRegistrationActivity.class, draft));
        });
        getSupportFragmentManager().setFragmentResultListener(DELETE_COURSE, this, (key, data) -> {
            if (pendingDelete >= 0 && pendingDelete < draft.courses.size()) {
                draft.courses.remove(pendingDelete);
                showCourses();
            }
            pendingDelete = -1;
        });
        showCourses();
    }

    private boolean addCourse() {
        Course course = fields.validated(draft, -1);
        if (course == null) return false;
        draft.courses.add(course);
        fields.clear();
        showCourses();
        return true;
    }

    private void showCourses() {
        courseList.removeAllViews();
        for (int i = 0; i < draft.courses.size(); i++) {
            int index = i;
            Course course = draft.courses.get(i);
            View card = getLayoutInflater().inflate(R.layout.item_course_card, courseList, false);
            ((TextView) card.findViewById(R.id.tvCourseTitle)).setText(course.getName());
            ((TextView) card.findViewById(R.id.tvCourseInfo)).setText(course.getFormattedInfo());
            card.setContentDescription(course.getName() + " 수정");
            card.setFocusable(true);
            card.setOnClickListener(v -> editCourse.launch(CourseModifyActivity.open(this, draft, index)));
            card.findViewById(R.id.btnDeleteCourse).setOnClickListener(v -> {
                pendingDelete = index;
                ConfirmDialog.show(getSupportFragmentManager(), DELETE_COURSE,
                        "해당 과목을 삭제할까요", "선택한 과목을 삭제할게요", "삭제");
            });
            courseList.addView(card);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        draft.save(out);
        fields.save(out);
        out.putInt(DELETE_COURSE, pendingDelete);
    }
}
