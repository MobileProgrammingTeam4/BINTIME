package com.team4.bintime.timetable;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.team4.bintime.R;

/**
 * [BT-006] 시간표 이미지 업로드 및 분석 화면
 * - 미선택 시: 분석하기 버튼 비활성화 (회색 버튼)
 * - 이미지 선택 시: 업로드 박스 디자인 변경 (연한 분홍 배경 & 빨간 보더, "이미지 다시 선택" 버튼), 분석하기 버튼 활성화 (빨간색 버튼)
 */
public class TimetableUploadActivity extends AppCompatActivity {

    private ImageView btnBack;
    private LinearLayout layoutUploadBox;
    private ImageView ivSelectedImage;
    private TextView tvSelectHintTitle;
    private TextView tvSelectHintSub;
    private FrameLayout btnSelectFromGallery;
    private TextView tvGalleryBtnText;
    private FrameLayout btnAnalyze;
    private TextView tvAnalyzeBtnText;

    private Uri selectedImageUri = null;

    // 갤러리 이미지 선택 결과를 처리하는 Launcher
    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedImageUri = result.getData().getData();
                    if (selectedImageUri != null) {
                        onImageSelected(selectedImageUri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 상단 시스템 상태바 아이콘을 검은색(어두운색)으로 적용 (흰색 배경 대응)
        setupLightStatusBar();

        setContentView(R.layout.activity_timetable_upload);

        initViews();
        setupListeners();
        updateAnalyzeButtonState();
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
        layoutUploadBox = findViewById(R.id.layoutUploadBox);
        ivSelectedImage = findViewById(R.id.ivSelectedImage);
        tvSelectHintTitle = findViewById(R.id.tvSelectHintTitle);
        tvSelectHintSub = findViewById(R.id.tvSelectHintSub);
        btnSelectFromGallery = findViewById(R.id.btnSelectFromGallery);
        tvGalleryBtnText = findViewById(R.id.tvGalleryBtnText);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        tvAnalyzeBtnText = findViewById(R.id.tvAnalyzeBtnText);
    }

    private void setupListeners() {
        // 뒤로가기 버튼
        btnBack.setOnClickListener(v -> finish());

        // 사진 보관함에서 선택 / 이미지 다시 선택 버튼
        btnSelectFromGallery.setOnClickListener(v -> openGallery());

        // 시간표 분석하기 버튼
        btnAnalyze.setOnClickListener(v -> {
            if (selectedImageUri != null) {
                Toast.makeText(this, "[BT-006] 시간표 이미지 분석을 시작합니다!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(TimetableUploadActivity.this, TimetableAnalysisResultActivity.class);
                startActivity(intent);
            }
        });
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        galleryLauncher.launch(intent);
    }

    /**
     * 이미지 선택 시 업로드 영역 레이아웃 및 분석 버튼 상태 변경
     */
    private void onImageSelected(Uri uri) {
        String fileName = getFileNameFromUri(uri);

        // 1. 업로드 박스 스타일 변경 (피그마 디자인: 선택된 상태의 은은한 분홍 테두리 박스)
        layoutUploadBox.setBackgroundResource(R.drawable.bg_upload_card_selected);

        // 2. 이미지 미리보기 설정
        ivSelectedImage.setImageURI(uri);
        ivSelectedImage.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // 3. 안내 텍스트 변경
        tvSelectHintTitle.setText(fileName);
        tvSelectHintSub.setText("이미지 선택됨 · AI 분석 준비 완료");
        tvSelectHintSub.setTextColor(Color.parseColor("#F84648"));

        // 4. 버튼 텍스트 변경 ("이미지 다시 선택")
        tvGalleryBtnText.setText("이미지 다시 선택");

        // 5. 분석하기 버튼 활성화
        updateAnalyzeButtonState();
    }

    /**
     * 분석하기 버튼 상태 변경 (이미지 미첨부 시 비활성화 회색, 첨부 시 활성화 빨간색)
     */
    private void updateAnalyzeButtonState() {
        boolean hasImage = (selectedImageUri != null);

        btnAnalyze.setEnabled(hasImage);
        btnAnalyze.setClickable(hasImage);

        if (hasImage) {
            btnAnalyze.setBackgroundResource(R.drawable.bg_btn_primary_pill);
            tvAnalyzeBtnText.setTextColor(Color.WHITE);
        } else {
            btnAnalyze.setBackgroundResource(R.drawable.bg_btn_disabled_pill);
            tvAnalyzeBtnText.setTextColor(Color.parseColor("#999999"));
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        if (result == null || result.isEmpty()) {
            result = "2026_2학기_시간표.png";
        }
        return result;
    }
}
