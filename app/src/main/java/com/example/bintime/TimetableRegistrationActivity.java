package com.example.bintime;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * [BT-009] 시간표 등록 및 공강 선택 화면 (시간표 등록)
 * - 9시부터 17시까지의 시간표 그리드 표시 (회색선 구분, 0.5dp 간격으로 셀 외곽선이 합쳐지도록 구현)
 * - 기본 수업 및 공강 데이터 사전 탑재
 * - 빈 공간 터치 시 공강(핑크색) 토글 및 실시간 렌더링
 */
public class TimetableRegistrationActivity extends AppCompatActivity {

    private ImageView btnBack;
    private LinearLayout layoutTimetableGrid;
    private FrameLayout btnCompleteRegistration;

    // 셀 타입: 0 = 빈칸 (Empty), 1 = 수업 (Class), 2 = 공강 (Free)
    private final int[][] cellType = new int[9][5];
    private final String[][] classTitle = new String[9][5];
    private final String[][] classLocation = new String[9][5];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLightStatusBar();
        setContentView(R.layout.activity_timetable_registration);

        initDefaultData();
        initViews();
        setupListeners();
        buildTimetableGrid();
    }

    private void setupLightStatusBar() {
        if (getWindow() != null) {
            getWindow().setStatusBarColor(Color.WHITE);
            WindowInsetsControllerCompat controller =
                    new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(true);
        }
    }

    private void initDefaultData() {
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 5; c++) {
                cellType[r][c] = 0;
                classTitle[r][c] = "";
                classLocation[r][c] = "";
            }
        }

        // 기본 수업 데이터 등록
        // 월요일 10시 (컴퓨터구조론, 공학관 302호)
        cellType[1][0] = 1; classTitle[1][0] = "컴퓨터구조론"; classLocation[1][0] = "공학관 302호";
        // 화요일 10시 (소프트웨어공학, 인문관 102호)
        cellType[1][1] = 1; classTitle[1][1] = "소프트웨어공학"; classLocation[1][1] = "인문관 102호";
        // 수요일 9시 (운영체제, 공학관 301호)
        cellType[0][2] = 1; classTitle[0][2] = "운영체제"; classLocation[0][2] = "공학관 301호";
        // 금요일 10시 ~ 11시 연속 수업 (인공지능, 사회관 201호)
        cellType[1][4] = 1; classTitle[1][4] = "인공지능"; classLocation[1][4] = "사회관 201호";
        cellType[2][4] = 1; classTitle[2][4] = "인공지능"; classLocation[2][4] = "사회관 201호";

        // 기본 공강 데이터 등록
        // 수요일 11시 ~ 12시 (공강)
        cellType[2][2] = 2;
        cellType[3][2] = 2;
        // 화요일 11시 (공강)
        cellType[2][1] = 2;
        // 목요일 11시 ~ 12시 (공강)
        cellType[2][3] = 2;
        cellType[3][3] = 2;
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        layoutTimetableGrid = findViewById(R.id.layoutTimetableGrid);
        btnCompleteRegistration = findViewById(R.id.btnCompleteRegistration);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnCompleteRegistration.setOnClickListener(v -> {
            Toast.makeText(this, "[BT-009] 시간표가 성공적으로 등록되었습니다!", Toast.LENGTH_SHORT).show();
            finishAffinity();
        });
    }

    private void buildTimetableGrid() {
        layoutTimetableGrid.removeAllViews();

        int[] hours = {9, 10, 11, 12, 13, 14, 15, 16, 17};

        for (int r = 0; r < hours.length; r++) {
            final int rowIndex = r;
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // 시간 표기 텍스트뷰 (예: "9")
            TextView tvHour = new TextView(this);
            LinearLayout.LayoutParams hourParams = new LinearLayout.LayoutParams(
                    dpToPx(32), dpToPx(36)
            );
            tvHour.setLayoutParams(hourParams);
            tvHour.setText(String.valueOf(hours[r]));
            tvHour.setTextColor(Color.parseColor("#999999"));
            tvHour.setTextSize(11);
            tvHour.setGravity(Gravity.CENTER);
            row.addView(tvHour);

            // 5일 (월, 화, 수, 목, 금) 그리드 셀
            for (int c = 0; c < 5; c++) {
                final int colIndex = c;

                FrameLayout cellContainer = new FrameLayout(this);
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, dpToPx(36), 1f
                );
                // 셀 간격을 0.5dp로 극도로 줄여서 외곽선이 거의 합쳐지게 설정
                cellParams.setMargins(dpToPx(0.5f), dpToPx(0.5f), dpToPx(0.5f), dpToPx(0.5f));
                cellContainer.setLayoutParams(cellParams);

                int type = cellType[rowIndex][colIndex]; // 0: empty, 1: class, 2: free

                if (type > 0) {
                    boolean isTop = (rowIndex == 0 || cellType[rowIndex - 1][colIndex] != type);

                    if (type == 1) {
                        // 수업 블록
                        cellContainer.setBackgroundResource(R.drawable.cell_class_single);

                        // 맨 위 칸에 수업 이름 및 위치 표시
                        if (isTop) {
                            LinearLayout infoLayout = new LinearLayout(this);
                            infoLayout.setOrientation(LinearLayout.VERTICAL);
                            FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            );
                            infoParams.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
                            infoLayout.setLayoutParams(infoParams);

                            TextView tvTitle = new TextView(this);
                            tvTitle.setText(classTitle[rowIndex][colIndex]);
                            tvTitle.setTextColor(Color.parseColor("#222222"));
                            tvTitle.setTextSize(10);
                            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                            infoLayout.addView(tvTitle);

                            TextView tvLoc = new TextView(this);
                            tvLoc.setText(classLocation[rowIndex][colIndex]);
                            tvLoc.setTextColor(Color.parseColor("#666666"));
                            tvLoc.setTextSize(9);
                            infoLayout.addView(tvLoc);

                            cellContainer.addView(infoLayout);
                        }

                    } else if (type == 2) {
                        // 공강 블록
                        cellContainer.setBackgroundResource(R.drawable.cell_free_single);

                        // 맨 위 칸에 빨간색 "공강" 라벨 표시
                        if (isTop) {
                            TextView tvLabel = new TextView(this);
                            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                            );
                            labelParams.gravity = Gravity.TOP | Gravity.START;
                            labelParams.setMargins(dpToPx(4), dpToPx(2), 0, 0);
                            tvLabel.setLayoutParams(labelParams);
                            tvLabel.setText("공강");
                            tvLabel.setTextColor(Color.parseColor("#F84648"));
                            tvLabel.setTextSize(10);
                            tvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                            cellContainer.addView(tvLabel);
                        }
                    }
                } else {
                    // 빈칸 (회색선 테두리)
                    cellContainer.setBackgroundResource(R.drawable.cell_empty);
                }

                // 클릭 시 빈칸 <-> 공강 토글 기능 (수업 칸은 유지)
                cellContainer.setClickable(true);
                cellContainer.setFocusable(true);
                cellContainer.setOnClickListener(v -> {
                    if (cellType[rowIndex][colIndex] == 1) {
                        Toast.makeText(this, "등록된 수업 칸입니다.", Toast.LENGTH_SHORT).show();
                    } else if (cellType[rowIndex][colIndex] == 2) {
                        cellType[rowIndex][colIndex] = 0;
                        Toast.makeText(this, "공강이 해제되었습니다.", Toast.LENGTH_SHORT).show();
                        buildTimetableGrid();
                    } else {
                        cellType[rowIndex][colIndex] = 2;
                        Toast.makeText(this, "공강으로 설정되었습니다.", Toast.LENGTH_SHORT).show();
                        buildTimetableGrid();
                    }
                });

                row.addView(cellContainer);
            }

            layoutTimetableGrid.addView(row);
        }
    }

    private int dpToPx(float dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
