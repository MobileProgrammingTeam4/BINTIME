package com.team4.bintime.timetable;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;

/**
 * 시간표 등록 방식 선택 화면 (수동 입력 / 이미지 업로드)
 */
public class TimetableSelectModeActivity extends AppCompatActivity {

    private ImageView btnBack;
    private LinearLayout btnManualInput;
    private LinearLayout btnImageUpload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timetable_select_mode);

        initViews();
        setupListeners();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnManualInput = findViewById(R.id.btnManualInput);
        btnImageUpload = findViewById(R.id.btnImageUpload);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnManualInput.setOnClickListener(v -> {
            Intent intent = new Intent(TimetableSelectModeActivity.this, CourseDirectInputActivity.class);
            startActivity(intent);
        });

        btnImageUpload.setOnClickListener(v -> {
            Intent intent = new Intent(TimetableSelectModeActivity.this, TimetableUploadActivity.class);
            startActivity(intent);
        });
    }
}
