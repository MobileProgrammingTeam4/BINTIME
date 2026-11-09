package com.team4.bintime.timetable;

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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.team4.bintime.R;

/**
 * [BT-007] 시간표 분석 결과 확인 화면 (인식 결과 확인)
 * - 이미지 업로드 후 분석 완료된 수업 목록 및 공강 분석 결과 표시
 * - 각 과목별 수정 및 삭제(모달 다이얼로그) 기능 구현
 */
public class TimetableAnalysisResultActivity extends AppCompatActivity {

    private ImageView btnBack;
    private ImageView btnMore;
    private TextView tvAnalysisDesc;
    private TextView tvCourseCountBadge;
    private LinearLayout layoutLectureList;
    private FrameLayout btnConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLightStatusBar();
        setContentView(R.layout.activity_timetable_analysis_result);

        initViews();
        setupListeners();
        populateMockLectures();
    }

    private void setupLightStatusBar() {
        if (getWindow() != null) {
            getWindow().setStatusBarColor(Color.WHITE);
            WindowInsetsControllerCompat controller =
                    new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(true);
        }
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnMore = findViewById(R.id.btnMore);
        tvAnalysisDesc = findViewById(R.id.tvAnalysisDesc);
        tvCourseCountBadge = findViewById(R.id.tvCourseCountBadge);
        layoutLectureList = findViewById(R.id.layoutLectureList);
        btnConfirm = findViewById(R.id.btnConfirm);

        if (tvAnalysisDesc != null) {
            tvAnalysisDesc.setText(getString(R.string.analysis_desc));
        }
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnMore.setOnClickListener(v -> Toast.makeText(this, "더보기 메뉴", Toast.LENGTH_SHORT).show());

        btnConfirm.setOnClickListener(v -> {
            Toast.makeText(this, "[BT-007] 시간표가 성공적으로 확정 및 등록되었습니다!", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, TimetableRegistrationActivity.class);
            startActivity(intent);
        });
    }

    private void populateMockLectures() {
        layoutLectureList.removeAllViews();

        String[][] lectures = {
                {"컴퓨터 구조론", "월 10:00 ~ 11:30 · 공학관 302호"},
                {"데이터베이스", "화 13:00 ~ 14:30 · 공학관 304호"},
                {"운영체제", "수 10:00 ~ 11:30 · 공학관 301호"},
                {"알고리즘", "목 13:00 ~ 14:30 · 공학관 303호"},
                {"소프트웨어공학", "금 09:00 ~ 10:30 · 인문관 102호"},
                {"인공지능", "월 15:00 ~ 16:30 · 사회관 201호"},
                {"프로그래밍기초", "수 13:00 ~ 14:30 · 자연관 101호"}
        };

        LayoutInflater inflater = LayoutInflater.from(this);
        for (String[] lecture : lectures) {
            View itemView = inflater.inflate(R.layout.item_lecture_review, layoutLectureList, false);
            TextView tvTitle = itemView.findViewById(R.id.tvCourseTitle);
            TextView tvInfo = itemView.findViewById(R.id.tvCourseInfo);
            FrameLayout btnEdit = itemView.findViewById(R.id.btnEditLecture);
            FrameLayout btnDelete = itemView.findViewById(R.id.btnDeleteLecture);

            if (tvTitle != null) tvTitle.setText(lecture[0]);
            if (tvInfo != null) tvInfo.setText(lecture[1]);

            if (btnEdit != null) {
                btnEdit.setOnClickListener(v -> {
                    Intent intent = new Intent(this, CourseModifyActivity.class);
                    intent.putExtra("course_title", lecture[0]);
                    intent.putExtra("course_info", lecture[1]);
                    startActivity(intent);
                });
            }

            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    showDeleteConfirmDialog(itemView, lecture[0]);
                });
            }

            layoutLectureList.addView(itemView);
        }

        updateCourseCountBadge();
    }

    /**
     * 과목 삭제 확인 커스텀 중앙 모달 다이얼로그 표시
     */
    private void showDeleteConfirmDialog(View itemView, String courseTitle) {
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

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                layoutLectureList.removeView(itemView);
                Toast.makeText(this, getString(R.string.msg_course_deleted, courseTitle), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                updateCourseCountBadge();
            });
        }

        dialog.show();
    }

    private void updateCourseCountBadge() {
        int count = layoutLectureList.getChildCount();
        if (tvCourseCountBadge != null) {
            tvCourseCountBadge.setText("2026년 2학기 · 수업 " + count + "개");
        }
    }
}
