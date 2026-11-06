// 파일 위치: app/src/main/java/com/example/bintime/TimetableSelectModeActivity.java
package com.example.bintime;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

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
        // 뒤로가기 버튼
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 1. 수동으로 입력하기 선택 (BT-005 수업 직접 입력 화면으로 연결)
        btnManualInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(TimetableSelectModeActivity.this, CourseDirectInputActivity.class);
                startActivity(intent);
            }
        });

        // 2. 이미지로 업로드하기 선택 (BT-006 시간표 이미지 업로드 화면으로 연결)
        btnImageUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(TimetableSelectModeActivity.this, TimetableUploadActivity.class);
                startActivity(intent);
            }
        });
    }
}
