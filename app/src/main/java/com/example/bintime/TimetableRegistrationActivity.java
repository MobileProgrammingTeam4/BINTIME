package com.example.bintime;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * [BT-009] 시간표 등록 및 공강 선택 화면 (시간표 등록)
 * - 모든 셀에 기본 회색 테두리가 표시됨 (빈 공간도 전체 회색 격자 표 형태로 유지)
 * - 수업/공강 셀이 2개 이상 연속으로 합쳐졌을 때만 내부 가로 경계선 제거 (외곽 테두리 유지)
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

        // 기본 수업 데이터 등록 (월 10시, 화 10시, 수 9시, 금 10~11시 연속 수업)
        cellType[1][0] = 1; classTitle[1][0] = "컴퓨터구조론"; classLocation[1][0] = "공학관 302호";
        cellType[1][1] = 1; classTitle[1][1] = "소프트웨어공학"; classLocation[1][1] = "인문관 102호";
        cellType[0][2] = 1; classTitle[0][2] = "운영체제"; classLocation[0][2] = "공학관 301호";
        cellType[1][4] = 1; classTitle[1][4] = "인공지능"; classLocation[1][4] = "사회관 201호";
        cellType[2][4] = 1; classTitle[2][4] = "인공지능"; classLocation[2][4] = "사회관 201호";

        // 기본 공강 데이터 등록 (수 11~12시 연속 공강 등)
        cellType[2][2] = 2;
        cellType[3][2] = 2;
        cellType[2][1] = 2;
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

                int type = cellType[rowIndex][colIndex]; // 0: empty, 1: class, 2: free

                // 빈칸(type 0)은 항시 4면 외곽선 전체 표시, 연속된 수업/공강(type 1,2)은 상/하 연결 상태 판별
                boolean isTop = (type == 0) || (rowIndex == 0 || cellType[rowIndex - 1][colIndex] != type);
                boolean isBottom = (type == 0) || (rowIndex == hours.length - 1 || cellType[rowIndex + 1][colIndex] != type);

                FrameLayout cellContainer = (FrameLayout) createCellView(type, isTop, isBottom);
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                        0, dpToPx(36), 1f
                );
                cellParams.setMargins(0, 0, 0, 0);
                cellContainer.setLayoutParams(cellParams);

                if (type > 0 && isTop) {
                    if (type == 1) {
                        // 수업 정보 표시
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
                    } else if (type == 2) {
                        // 공강 라벨 표시
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

                // 클릭 시 빈칸 <-> 공강 토글 기능 (수업 칸은 안내 토스트)
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

    /**
     * 셀 외곽선을 설정하는 메서드:
     * - 모든 셀(빈칸 포함)은 기본 외곽선을 가진 표 형태로 나타남
     * - 수업/공강 셀이 2개 이상 연속 합쳐지면 내부 가로 경계선만 제거됨
     */
    private View createCellView(int type, boolean isTop, boolean isBottom) {
        FrameLayout cell = new FrameLayout(this);
        int bgColor;
        int borderColor;

        if (type == 1) {
            bgColor = Color.parseColor("#F7F7F8");
            borderColor = Color.parseColor("#E5E5E5");
        } else if (type == 2) {
            bgColor = Color.parseColor("#FFF5F5");
            borderColor = Color.parseColor("#FFD6D6");
        } else {
            bgColor = Color.parseColor("#FFFFFF");
            borderColor = Color.parseColor("#E5E5E5");
        }

        cell.setBackgroundColor(bgColor);

        // 좌/우 테두리는 항시 그림
        View leftLine = new View(this);
        leftLine.setLayoutParams(new FrameLayout.LayoutParams(dpToPx(1f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START));
        leftLine.setBackgroundColor(borderColor);
        cell.addView(leftLine);

        View rightLine = new View(this);
        rightLine.setLayoutParams(new FrameLayout.LayoutParams(dpToPx(1f), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END));
        rightLine.setBackgroundColor(borderColor);
        cell.addView(rightLine);

        // 상단 테두리 (isTop 이거나 빈칸일 때만 그림)
        if (isTop) {
            View topLine = new View(this);
            FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(1f));
            topParams.gravity = Gravity.TOP;
            topLine.setLayoutParams(topParams);
            topLine.setBackgroundColor(borderColor);
            cell.addView(topLine);
        }

        // 하단 테두리 (isBottom 이거나 빈칸일 때만 그림)
        if (isBottom) {
            View bottomLine = new View(this);
            FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(1f));
            bottomParams.gravity = Gravity.BOTTOM;
            bottomLine.setLayoutParams(bottomParams);
            bottomLine.setBackgroundColor(borderColor);
            cell.addView(bottomLine);
        }

        return cell;
    }

    private int dpToPx(float dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
