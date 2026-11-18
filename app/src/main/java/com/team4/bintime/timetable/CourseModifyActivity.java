package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;

public class CourseModifyActivity extends AppCompatActivity {
    private static final String EXTRA_INDEX = "course_index";
    private static final String CANCEL_EDIT = "cancel_timetable_course_edit";
    private CourseFields fields;
    private Course original;
    private TextView save;

    static Intent open(Context context, TimetableDraft draft, int index) {
        return TimetableDraft.open(context, CourseModifyActivity.class, draft).putExtra(EXTRA_INDEX, index);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TimetableDraft draft = TimetableDraft.read(getIntent());
        int index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        if (draft == null || index < -1 || index >= draft.courses.size()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_course_direct_input);
        boolean adding = index == -1;
        original = adding ? CourseFields.empty() : draft.courses.get(index);
        save = findViewById(R.id.btnSaveAndRegister);
        fields = new CourseFields(this, CourseFields.restore(state, original), this::updateSaveButton);
        // [BT-026] 원래 강의실이 없던 수업(인식 null·기존 null)만 비워 둘 수 있다. 추가는 필수.
        if (!adding && CourseFormRules.classroomOptional(original)) fields.allowEmptyClassroom();
        ((TextView) findViewById(R.id.tvToolbarTitle)).setText(adding ? "수업 추가하기" : "수업 수정하기");
        ((TextView) findViewById(R.id.course_form_title)).setText(adding
                ? "수업 정보를 입력해 주세요" : "수업 정보를 수정해 주세요");
        TextView notice = findViewById(R.id.course_form_notice);
        notice.setPadding(0, ChipViews.dp(this, 16), 0, 0);
        notice.setText(adding
                ? "추가한 내용은 다음페이지에서 다시 확인할 수 있어요"
                : "수정한 내용은 다음페이지에서 다시 확인할 수 있어요");
        findViewById(R.id.btnAddCourseAndContinue).setVisibility(View.GONE);
        findViewById(R.id.layoutCourseList).setVisibility(View.GONE);
        save.setText(adding ? "추가하기" : "수정하기");
        updateSaveButton();
        save.setOnClickListener(v -> {
            Course course = fields.validated(draft, index);
            if (course == null) return;
            if (!adding) course.keepIdentityOf(original); // 같은 저장소 수업을 가리키고 교수·색상 유지
            if (adding) draft.courses.add(course);
            else draft.courses.set(index, course);
            setResult(RESULT_OK, draft.result());
            finish();
        });

        getSupportFragmentManager().setFragmentResultListener(CANCEL_EDIT, this, (key, result) -> finish());
        findViewById(R.id.btnBack).setOnClickListener(v -> requestBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { requestBack(); }
        });
    }

    private void requestBack() {
        if (CourseFormRules.sameAsOriginal(original, fields.value())) {
            finish();
            return;
        }
        ConfirmDialog.show(getSupportFragmentManager(), CANCEL_EDIT, "수정을 취소할까요",
                "작성중인 정보는 저장되지 않아요", "수정 취소하기", "닫기");
    }

    private void updateSaveButton() {
        if (fields == null) return;
        boolean changed = !CourseFormRules.sameAsOriginal(original, fields.value());
        save.setEnabled(changed);
        save.setBackgroundResource(changed ? R.drawable.bg_pill_primary : R.drawable.bg_btn_disabled_pill);
        save.setTextColor(ContextCompat.getColor(this, changed ? R.color.bg_card : R.color.timetable_disabled_text));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (fields != null) fields.save(out);
    }
}
