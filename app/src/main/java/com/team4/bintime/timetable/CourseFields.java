package com.team4.bintime.timetable;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;

/** Field bindings shared by direct input and course editing. */
final class CourseFields {
    private final AppCompatActivity activity;
    private final EditText name;
    private final EditText classroom;
    private final TextView day;
    private final TextView start;
    private final TextView end;

    CourseFields(AppCompatActivity activity, Course initial, Runnable onChange) {
        this.activity = activity;
        name = activity.findViewById(R.id.etCourseName);
        classroom = activity.findViewById(R.id.etClassroom);
        day = activity.findViewById(R.id.tvSelectedDay);
        start = activity.findViewById(R.id.tvStartTime);
        end = activity.findViewById(R.id.tvEndTime);
        name.setText(initial.getName());
        classroom.setText(initial.getClassroom());
        day.setText(initial.getShortDay() + "요일");
        start.setText(initial.getStartTime());
        end.setText(initial.getEndTime());
        bindPicker(R.id.layoutDayPicker, day, "요일을 선택해 주세요", true, onChange);
        bindPicker(R.id.layoutStartTimePicker, start, "시작 시간을 선택해 주세요", false, onChange);
        bindPicker(R.id.layoutEndTimePicker, end, "종료 시간을 선택해 주세요", false, onChange);
        if (onChange != null) {
            TextWatcher watch = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { onChange.run(); }
                @Override public void afterTextChanged(Editable text) {}
            };
            name.addTextChangedListener(watch);
            classroom.addTextChangedListener(watch);
        }
    }

    private void bindPicker(int fieldId, TextView value, String title, boolean weekday, Runnable onChange) {
        String resultKey = "course_field_" + fieldId;
        activity.getSupportFragmentManager().setFragmentResultListener(resultKey, activity, (key, result) -> {
            value.setText(result.getString(CoursePickerSheet.VALUE));
            if (onChange != null) onChange.run();
        });
        activity.findViewById(fieldId).setOnClickListener(v -> {
            if (activity.getSupportFragmentManager().findFragmentByTag(resultKey) != null) return;
            CoursePickerSheet.create(resultKey, title, value.getText().toString(), weekday)
                    .show(activity.getSupportFragmentManager(), resultKey);
        });
    }

    Course value() {
        return new Course(name.getText().toString().trim(), classroom.getText().toString().trim(),
                day.getText().toString().substring(0, 1), start.getText().toString(), end.getText().toString());
    }

    boolean hasInput() {
        return name.length() > 0 || classroom.length() > 0;
    }

    void clear() {
        name.setText("");
        classroom.setText("");
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(name.getWindowToken(), 0);
        name.clearFocus();
        classroom.clearFocus();
    }

    Course validated(TimetableDraft draft, int editIndex) {
        Course course = value();
        if (course.getName().isEmpty()) {
            name.setError("강의명을 입력해 주세요.");
            name.requestFocus();
            return null;
        }
        if (course.getClassroom().isEmpty()) {
            classroom.setError("강의실을 입력해 주세요.");
            classroom.requestFocus();
            return null;
        }
        if (course.getStartMinute() >= course.getEndMinute()) {
            Toast.makeText(activity, "종료 시간은 시작 시간보다 늦어야 해요.", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (draft.overlaps(course, editIndex)) {
            Toast.makeText(activity, "같은 요일에 시간이 겹치는 수업이 있어요.", Toast.LENGTH_SHORT).show();
            return null;
        }
        return course;
    }

    void save(Bundle out) {
        out.putSerializable("course_fields", value());
    }

    static Course restore(Bundle state, Course initial) {
        Course restored = state == null ? null
                : (Course) state.getSerializable("course_fields");
        return restored == null ? initial : restored;
    }

    static Course empty() {
        return new Course("", "", "월", "10:00", "11:30");
    }
}
