package com.example.bintime;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * [BT-005] 수업 직접 입력 화면
 * - 요일 버튼 클릭 시 커스텀 중앙 팝업 다이얼로그 (체크 형태 선택) 실행
 * - 시간 버튼 클릭 시 커스텀 중앙 휠 피커(오전/오후, 00~12, 00~55) 실행
 * - 삭제 버튼 클릭 시 커스텀 중앙 삭제 확인 다이얼로그 실행
 * - 수업 목록 존재 여부에 따라 하단 [저장하고 시간표 등록으로] 버튼 동적 활성화/비활성화
 */
public class CourseDirectInputActivity extends AppCompatActivity {

    private ImageView btnBack;
    private EditText etCourseName;
    private EditText etClassroom;
    private LinearLayout layoutDayPicker;
    private TextView tvSelectedDay;
    private LinearLayout layoutStartTimePicker;
    private TextView tvStartTime;
    private LinearLayout layoutEndTimePicker;
    private TextView tvEndTime;
    private FrameLayout btnAddCourseAndContinue;
    private LinearLayout layoutCourseList;
    private FrameLayout btnSaveAndRegister;
    private TextView tvSaveAndRegisterText;

    // 시간 피커 데이터
    private final String[] amPmValues = {"오전", "오후"};
    private final String[] hourValues = {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    private final String[] minuteValues = {"00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course_direct_input);

        initViews();
        setupListeners();

        // 초기 목업 데이터 (피그마 디자인에 있는 기본 1개 카드)
        addCourseCard("컴퓨터 구조론", "월 10:00 ~ 11:30 · 공학관 302호");
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        etCourseName = findViewById(R.id.etCourseName);
        etClassroom = findViewById(R.id.etClassroom);
        layoutDayPicker = findViewById(R.id.layoutDayPicker);
        tvSelectedDay = findViewById(R.id.tvSelectedDay);
        layoutStartTimePicker = findViewById(R.id.layoutStartTimePicker);
        tvStartTime = findViewById(R.id.tvStartTime);
        layoutEndTimePicker = findViewById(R.id.layoutEndTimePicker);
        tvEndTime = findViewById(R.id.tvEndTime);
        btnAddCourseAndContinue = findViewById(R.id.btnAddCourseAndContinue);
        layoutCourseList = findViewById(R.id.layoutCourseList);
        btnSaveAndRegister = findViewById(R.id.btnSaveAndRegister);
        tvSaveAndRegisterText = findViewById(R.id.tvSaveAndRegisterText);
    }

    private void setupListeners() {
        // 뒤로가기 버튼
        btnBack.setOnClickListener(v -> finish());

        // 요일 선택 버튼 -> 커스텀 중앙 요일 선택 다이얼로그
        layoutDayPicker.setOnClickListener(v -> showCustomDayPickerDialog());

        // 시작 시간 버튼 -> 커스텀 시간 선택 중앙 다이얼로그
        layoutStartTimePicker.setOnClickListener(v -> {
            showCustomTimePickerDialog("시작 시간을 선택해 주세요", tvStartTime, 10, 0); // 기본 10:00
        });

        // 종료 시간 버튼 -> 커스텀 시간 선택 중앙 다이얼로그
        layoutEndTimePicker.setOnClickListener(v -> {
            showCustomTimePickerDialog("종료 시간을 선택해 주세요", tvEndTime, 11, 30); // 기본 11:30
        });

        // 수업 추가 버튼
        btnAddCourseAndContinue.setOnClickListener(v -> handleAddCourse());

        // 저장하고 시간표 등록으로 버튼
        btnSaveAndRegister.setOnClickListener(v -> {
            if (layoutCourseList.getChildCount() > 0) {
                Toast.makeText(CourseDirectInputActivity.this,
                        "[BT-005] 시간표가 성공적으로 저장되었습니다!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(CourseDirectInputActivity.this, TimetableRegistrationActivity.class);
                startActivity(intent);
            }
        });
    }

    /**
     * 수업 등록 카드 개수에 따라 하단 저장 버튼 활성화(빨간색) / 비활성화(회색) 변경
     */
    private void updateSaveButtonState() {
        boolean hasCourses = (layoutCourseList.getChildCount() > 0);
        btnSaveAndRegister.setEnabled(hasCourses);
        btnSaveAndRegister.setClickable(hasCourses);

        if (hasCourses) {
            btnSaveAndRegister.setBackgroundResource(R.drawable.bg_btn_primary_pill);
            if (tvSaveAndRegisterText != null) tvSaveAndRegisterText.setTextColor(Color.WHITE);
        } else {
            btnSaveAndRegister.setBackgroundResource(R.drawable.bg_btn_disabled_pill);
            if (tvSaveAndRegisterText != null) tvSaveAndRegisterText.setTextColor(Color.parseColor("#999999"));
        }
    }

    /**
     * 피그마 디자인을 적용한 커스텀 요일 선택 중앙 다이얼로그
     */
    private void showCustomDayPickerDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_day_picker, null);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        String currentSelected = tvSelectedDay.getText().toString();

        int[] itemIds = {
                R.id.itemMon, R.id.itemTue, R.id.itemWed, R.id.itemThu, R.id.itemFri, R.id.itemSat, R.id.itemSun
        };
        int[] checkIds = {
                R.id.ivMonCheck, R.id.ivTueCheck, R.id.ivWedCheck, R.id.ivThuCheck, R.id.ivFriCheck, R.id.ivSatCheck, R.id.ivSunCheck
        };
        int[] textIds = {
                R.id.tvMon, R.id.tvTue, R.id.tvWed, R.id.tvThu, R.id.tvFri, R.id.tvSat, R.id.tvSun
        };

        for (int i = 0; i < itemIds.length; i++) {
            LinearLayout item = dialogView.findViewById(itemIds[i]);
            ImageView ivCheck = dialogView.findViewById(checkIds[i]);
            TextView tvDay = dialogView.findViewById(textIds[i]);

            String dayName = tvDay.getText().toString();

            if (dayName.equals(currentSelected)) {
                ivCheck.setVisibility(View.VISIBLE);
            } else {
                ivCheck.setVisibility(View.GONE);
            }

            item.setOnClickListener(v -> {
                tvSelectedDay.setText(dayName);
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    /**
     * 피그마 디자인을 적용한 커스텀 시간 선택 중앙 다이얼로그
     */
    private void showCustomTimePickerDialog(String title, TextView targetTextView, int defaultHour, int defaultMinute) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bottom_time_picker, null);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        TextView tvTitle = dialogView.findViewById(R.id.tvTimePickerTitle);
        NumberPicker npAmPm = dialogView.findViewById(R.id.npAmPm);
        NumberPicker npHour = dialogView.findViewById(R.id.npHour);
        NumberPicker npMinute = dialogView.findViewById(R.id.npMinute);
        FrameLayout btnCancel = dialogView.findViewById(R.id.btnCancelTime);
        FrameLayout btnConfirm = dialogView.findViewById(R.id.btnConfirmTime);

        tvTitle.setText(title);

        String currentText = targetTextView.getText().toString();
        if (currentText.contains(":")) {
            String[] parts = currentText.split(":");
            try {
                defaultHour = Integer.parseInt(parts[0].trim());
                defaultMinute = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {}
        }

        npAmPm.setMinValue(0);
        npAmPm.setMaxValue(amPmValues.length - 1);
        npAmPm.setDisplayedValues(amPmValues);
        npAmPm.setValue(defaultHour >= 12 ? 1 : 0);

        npHour.setMinValue(0);
        npHour.setMaxValue(hourValues.length - 1);
        npHour.setDisplayedValues(hourValues);
        int hour12 = defaultHour % 12;
        npHour.setValue(hour12);

        npMinute.setMinValue(0);
        npMinute.setMaxValue(minuteValues.length - 1);
        npMinute.setDisplayedValues(minuteValues);
        int minuteIndex = Math.min(defaultMinute / 5, minuteValues.length - 1);
        npMinute.setValue(minuteIndex);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String selectedHour = hourValues[npHour.getValue()];
            String selectedMinute = minuteValues[npMinute.getValue()];
            String formattedTime = selectedHour + ":" + selectedMinute;

            targetTextView.setText(formattedTime);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void handleAddCourse() {
        String courseName = etCourseName.getText().toString().trim();
        String classroom = etClassroom.getText().toString().trim();
        String day = tvSelectedDay.getText().toString();
        String start = tvStartTime.getText().toString();
        String end = tvEndTime.getText().toString();

        if (courseName.isEmpty()) courseName = "알고리즘 및 실습";
        if (classroom.isEmpty()) classroom = "IT관 201호";

        String shortDay = !day.isEmpty() ? day.substring(0, 1) : "월";
        String courseInfo = String.format("%s %s ~ %s · %s", shortDay, start, end, classroom);

        addCourseCard(courseName, courseInfo);

        etCourseName.setText("");
        etClassroom.setText("");
        Toast.makeText(this, "'" + courseName + "' 수업이 추가되었습니다.", Toast.LENGTH_SHORT).show();
    }

    private void addCourseCard(String title, String info) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View cardView = inflater.inflate(R.layout.item_course_card, layoutCourseList, false);

        TextView tvTitle = cardView.findViewById(R.id.tvCourseTitle);
        TextView tvInfo = cardView.findViewById(R.id.tvCourseInfo);
        FrameLayout btnDelete = cardView.findViewById(R.id.btnDeleteCourse);

        tvTitle.setText(title);
        tvInfo.setText(info);

        btnDelete.setOnClickListener(v -> showDeleteCourseConfirmDialog(cardView, title));

        layoutCourseList.addView(cardView);

        // 수업 카드가 추가되었으므로 저장 버튼 활성화
        updateSaveButtonState();
    }

    /**
     * 수업 카드 삭제 확인 커스텀 중앙 다이얼로그
     */
    private void showDeleteCourseConfirmDialog(View cardView, String courseTitle) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_course, null);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.84);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        FrameLayout btnCancel = dialogView.findViewById(R.id.btnCancelDelete);
        FrameLayout btnConfirm = dialogView.findViewById(R.id.btnConfirmDelete);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            layoutCourseList.removeView(cardView);
            Toast.makeText(this, "'" + courseTitle + "' 수업이 삭제되었습니다.", Toast.LENGTH_SHORT).show();
            dialog.dismiss();

            // 수업 카드 삭제 후 남아있는 카드가 없으면 저장 버튼 비활성화
            updateSaveButtonState();
        });

        dialog.show();
    }
}
