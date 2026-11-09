package com.team4.bintime.timetable;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.team4.bintime.R;

/**
 * [BT-008] 수업 수정하기 화면
 * - 기존 수업 정보를 받아와 수정 가능하도록 제공
 * - 변경된 내용이 있을 때만 하단 [수정 완료] 버튼 활성화
 */
public class CourseModifyActivity extends AppCompatActivity {

    private ImageView btnBack;
    private EditText etCourseName;
    private EditText etClassroom;
    private LinearLayout layoutDayPicker;
    private TextView tvSelectedDay;
    private LinearLayout layoutStartTimePicker;
    private TextView tvStartTime;
    private LinearLayout layoutEndTimePicker;
    private TextView tvEndTime;
    private FrameLayout btnModify;
    private TextView tvModifyBtnText;

    private String initialCourseName = "";
    private String initialClassroom = "";
    private String initialDay = "월요일";
    private String initialStartTime = "10:00";
    private String initialEndTime = "11:30";

    // 시간 피커 데이터
    private final String[] amPmValues = {"오전", "오후"};
    private final String[] hourValues = {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    private final String[] minuteValues = {"00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLightStatusBar();
        setContentView(R.layout.activity_course_modify);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (hasChanges()) {
                    showCancelModifyDialog();
                } else {
                    finish();
                }
            }
        });

        parseIntentData();
        initViews();
        setupListeners();
        updateModifyButtonState();
    }

    private void setupLightStatusBar() {
        if (getWindow() != null) {
            getWindow().setStatusBarColor(Color.WHITE);
            WindowInsetsControllerCompat controller =
                    new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(true);
        }
    }

    private void parseIntentData() {
        if (getIntent() != null) {
            String title = getIntent().getStringExtra("course_title");
            String info = getIntent().getStringExtra("course_info");

            if (title != null && !title.isEmpty()) {
                initialCourseName = title;
            } else {
                initialCourseName = "컴퓨터 구조론";
            }

            if (info != null && !info.isEmpty()) {
                try {
                    String[] parts = info.split("·");
                    if (parts.length > 1) {
                        initialClassroom = parts[1].trim();
                    }
                    String left = parts[0].trim();
                    String[] dayTime = left.split(" ");
                    if (dayTime.length > 0) {
                        String d = dayTime[0].trim();
                        if (d.startsWith("월")) initialDay = "월요일";
                        else if (d.startsWith("화")) initialDay = "화요일";
                        else if (d.startsWith("수")) initialDay = "수요일";
                        else if (d.startsWith("목")) initialDay = "목요일";
                        else if (d.startsWith("금")) initialDay = "금요일";
                        else if (d.startsWith("토")) initialDay = "토요일";
                        else if (d.startsWith("일")) initialDay = "일요일";
                    }
                    if (left.contains("~")) {
                        String[] times = left.split("~");
                        String t0 = times[0].trim();
                        if (t0.contains(" ")) {
                            initialStartTime = t0.substring(t0.indexOf(' ') + 1).trim();
                        }
                        initialStartTime = times[0].replaceAll("[^0-9:]", "");
                        initialEndTime = times[1].replaceAll("[^0-9:]", "");
                    }
                } catch (Exception e) {
                    initialClassroom = "공학관 302호";
                    initialStartTime = "10:00";
                    initialEndTime = "11:30";
                }
            } else {
                initialClassroom = "공학관 302호";
            }
        }
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
        btnModify = findViewById(R.id.btnModify);
        tvModifyBtnText = findViewById(R.id.tvModifyBtnText);

        etCourseName.setText(initialCourseName);
        etClassroom.setText(initialClassroom);
        tvSelectedDay.setText(initialDay);
        tvStartTime.setText(initialStartTime);
        tvEndTime.setText(initialEndTime);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> {
            if (hasChanges()) {
                showCancelModifyDialog();
            } else {
                finish();
            }
        });

        layoutDayPicker.setOnClickListener(v -> showCustomDayPickerDialog());

        layoutStartTimePicker.setOnClickListener(v -> showCustomTimePickerDialog(getString(R.string.title_start_time), tvStartTime, 10, 0));

        layoutEndTimePicker.setOnClickListener(v -> showCustomTimePickerDialog(getString(R.string.title_end_time), tvEndTime, 11, 30));

        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateModifyButtonState();
            }
        };

        etCourseName.addTextChangedListener(textWatcher);
        etClassroom.addTextChangedListener(textWatcher);

        btnModify.setOnClickListener(v -> {
            if (hasChanges()) {
                String updatedName = etCourseName.getText().toString().trim();
                Toast.makeText(this, getString(R.string.msg_course_modified, updatedName), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void showCancelModifyDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_cancel_modify, null);
        dialog.setContentView(dialogView);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.84);
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        FrameLayout btnCancel = dialogView.findViewById(R.id.btnCancelModal);
        FrameLayout btnConfirm = dialogView.findViewById(R.id.btnConfirmModal);

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                finish();
            });
        }

        dialog.show();
    }

    private boolean hasChanges() {
        String name = etCourseName.getText().toString().trim();
        String room = etClassroom.getText().toString().trim();
        String day = tvSelectedDay.getText().toString().trim();
        String start = tvStartTime.getText().toString().trim();
        String end = tvEndTime.getText().toString().trim();

        return !name.equals(initialCourseName) ||
                !room.equals(initialClassroom) ||
                !day.equals(initialDay) ||
                !start.equals(initialStartTime) ||
                !end.equals(initialEndTime);
    }

    private void updateModifyButtonState() {
        boolean changed = hasChanges();

        btnModify.setEnabled(changed);
        btnModify.setClickable(changed);

        if (changed) {
            btnModify.setBackgroundResource(R.drawable.bg_btn_primary_pill);
            if (tvModifyBtnText != null) tvModifyBtnText.setTextColor(Color.WHITE);
        } else {
            btnModify.setBackgroundResource(R.drawable.bg_btn_disabled_pill);
            if (tvModifyBtnText != null) tvModifyBtnText.setTextColor(Color.parseColor("#999999"));
        }
    }

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
                updateModifyButtonState();
            });
        }

        dialog.show();
    }

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
            updateModifyButtonState();
        });

        dialog.show();
    }
}
