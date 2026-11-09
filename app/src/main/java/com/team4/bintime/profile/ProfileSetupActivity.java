package com.team4.bintime.profile;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.MockAuthRepository;

import java.util.ArrayList;

/**
 * 프로필 최초 설정 및 프로필 수정 화면 (Figma 41:10218, 102:2684, 122:1959).
 * (BT-004 / Issue #4)
 *
 * 마이페이지와 같은 {@link MockProfileRepository#shared()} 를 사용하므로 저장 즉시 마이페이지에 반영된다.
 * 화면 회전 시에는 저장소를 다시 읽지 않고 작성 중인 값(입력·선택 태그)을 그대로 복원한다.
 */
public class ProfileSetupActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_USER_EMAIL = "userEmail";
    public static final int MODE_CREATE = 0;
    public static final int MODE_EDIT = 1;

    private static final String STATE_MODE = "profileMode";
    private static final String STATE_PROFILE = "userProfile";
    private static final String STATE_SELECTED_TAGS = "selectedTags";

    private int mode = MODE_CREATE;
    private final MockProfileRepository profileRepo = MockProfileRepository.shared();
    private UserProfile profile = UserProfile.empty();
    private final ArrayList<String> selectedTags = new ArrayList<>();

    private ImageView imgProfilePhoto;
    private TextView tvPhotoLabel;
    private EditText etNickname;
    private TextView tvNicknameCount;
    private TextView tvNicknameError;
    private EditText etDepartment;
    private TextView tvDepartmentError;
    private Spinner spinnerGender;
    private Spinner spinnerGrade;
    private TextView tvGenderNotice;
    private EditText etBio;
    private TextView tvBioCount;
    private TextView tvAiFeedback;
    private ChipGroup chipGroupAiTags;
    private ChipGroup chipGroupDefaultTags;
    private AppCompatButton btnSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_CREATE);
        boolean restored = false;

        if (savedInstanceState != null) {
            mode = savedInstanceState.getInt(STATE_MODE, mode);
            UserProfile restoredProfile = (UserProfile) savedInstanceState.getSerializable(STATE_PROFILE);
            ArrayList<String> restoredTags = savedInstanceState.getStringArrayList(STATE_SELECTED_TAGS);
            if (restoredProfile != null) {
                profile = restoredProfile;
                selectedTags.clear();
                if (restoredTags != null) selectedTags.addAll(restoredTags);
                restored = true;
            }
        }

        initViews();
        setupSpinners();
        // 입력값과 스피너 선택은 View 상태 복원으로 유지되므로, 회전 시에는 저장소 값으로 덮어쓰지 않는다.
        if (restored) {
            bindPhoto();
        } else if (mode == MODE_EDIT) {
            loadProfileData();
        } else {
            initCreateProfile();
        }
        setupTags();
        bindEvents();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MODE, mode);
        outState.putSerializable(STATE_PROFILE, profile);
        outState.putStringArrayList(STATE_SELECTED_TAGS, selectedTags);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> handleBackAction());

        imgProfilePhoto = findViewById(R.id.imgProfilePhoto);
        tvPhotoLabel = findViewById(R.id.tvPhotoLabel);
        etNickname = findViewById(R.id.etNickname);
        tvNicknameCount = findViewById(R.id.tvNicknameCount);
        tvNicknameError = findViewById(R.id.tvNicknameError);
        etDepartment = findViewById(R.id.etDepartment);
        tvDepartmentError = findViewById(R.id.tvDepartmentError);
        spinnerGender = findViewById(R.id.spinnerGender);
        spinnerGrade = findViewById(R.id.spinnerGrade);
        tvGenderNotice = findViewById(R.id.tvGenderNotice);
        etBio = findViewById(R.id.etBio);
        tvBioCount = findViewById(R.id.tvBioCount);
        tvAiFeedback = findViewById(R.id.tvAiFeedback);
        chipGroupAiTags = findViewById(R.id.chipGroupAiTags);
        chipGroupDefaultTags = findViewById(R.id.chipGroupDefaultTags);
        btnSubmit = findViewById(R.id.btnSubmit);

        if (mode == MODE_EDIT) {
            tvPhotoLabel.setText(R.string.profile_change_photo);
            btnSubmit.setText(R.string.profile_save_button);
            // 성별은 1회 선택 후 수정 불가 불변 정책
            spinnerGender.setEnabled(false);
            spinnerGender.setAlpha(0.6f);
        } else {
            tvPhotoLabel.setText(R.string.profile_add_photo);
            btnSubmit.setText(R.string.profile_start_button);
            spinnerGender.setEnabled(true);
            spinnerGender.setAlpha(1.0f);
        }
    }

    private void setupSpinners() {
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"여성", "남성"}
        );
        spinnerGender.setAdapter(genderAdapter);

        ArrayAdapter<String> gradeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1학년", "2학년", "3학년", "4학년"}
        );
        spinnerGrade.setAdapter(gradeAdapter);
    }

    private void loadProfileData() {
        profileRepo.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile user) {
                profile = user;
                etNickname.setText(profile.getNickname());
                etDepartment.setText(profile.getDepartment());
                etBio.setText(profile.getBio());

                if ("남성".equals(profile.getGender())) spinnerGender.setSelection(1);
                else spinnerGender.setSelection(0);

                if ("1학년".equals(profile.getGrade())) spinnerGrade.setSelection(0);
                else if ("2학년".equals(profile.getGrade())) spinnerGrade.setSelection(1);
                else if ("3학년".equals(profile.getGrade())) spinnerGrade.setSelection(2);
                else spinnerGrade.setSelection(3);

                bindPhoto();

                selectedTags.clear();
                if (profile.getInterestTags() != null) {
                    selectedTags.addAll(profile.getInterestTags());
                }

                updateCharCounters();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ProfileSetupActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 최초 생성: 시연용 기본 프로필을 채우지 않고 인증된 계정 정보만 연결한 빈 폼으로 시작한다. */
    private void initCreateProfile() {
        profile = UserProfile.empty();
        profile.setUid(MockAuthRepository.shared().getCurrentUid());
        profile.setEmail(getIntent().getStringExtra(EXTRA_USER_EMAIL));
        selectedTags.clear();
        updateCharCounters();
    }

    private void bindPhoto() {
        if (profile.getPhotoResId() != 0) {
            imgProfilePhoto.setImageResource(profile.getPhotoResId());
        }
    }

    private void setupTags() {
        chipGroupAiTags.removeAllViews();
        for (String tag : profileRepo.getAiRecommendedTags()) {
            chipGroupAiTags.addView(createTagChip(tag));
        }

        chipGroupDefaultTags.removeAllViews();
        for (String tag : profileRepo.getDefaultTags()) {
            chipGroupDefaultTags.addView(createTagChip(tag));
        }
    }

    private Chip createTagChip(String tag) {
        Chip chip = new Chip(this);
        chip.setText("#" + tag);
        chip.setCheckable(true);
        chip.setChecked(selectedTags.contains(tag));
        updateChipStyle(chip, chip.isChecked());

        chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!selectedTags.contains(tag)) selectedTags.add(tag);
            } else {
                selectedTags.remove(tag);
            }
            updateChipStyle(chip, isChecked);
        });
        return chip;
    }

    private void updateChipStyle(Chip chip, boolean checked) {
        if (checked) {
            chip.setChipBackgroundColorResource(R.color.action_subtle);
            chip.setTextColor(ContextCompat.getColor(this, R.color.action_primary));
            chip.setChipStrokeColorResource(R.color.action_primary);
            chip.setChipStrokeWidth(1.5f);
        } else {
            chip.setChipBackgroundColorResource(R.color.bg_page);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            chip.setChipStrokeColorResource(R.color.border_default);
            chip.setChipStrokeWidth(1.0f);
        }
    }

    private void bindEvents() {
        etNickname.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                int len = s != null ? s.length() : 0;
                tvNicknameCount.setText(len + "/10");
                tvNicknameError.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etDepartment.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                tvDepartmentError.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        etBio.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                int len = s != null ? s.length() : 0;
                tvBioCount.setText(len + "/150");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // AI로 자기소개 작성하기 버튼 클릭
        findViewById(R.id.btnAiBio).setOnClickListener(v -> {
            profileRepo.generateAiBio("자료구조, 월요일 공강", new ProfileRepository.Callback<String>() {
                @Override
                public void onSuccess(String result) {
                    etBio.setText(result);
                    tvAiFeedback.setVisibility(View.VISIBLE);
                    Toast.makeText(ProfileSetupActivity.this, "AI 자기소개가 작성되었어요!", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {}
            });
        });

        // 프로필 사진 변경 클릭 시 토스트
        findViewById(R.id.layoutPhotoContainer).setOnClickListener(v ->
                Toast.makeText(this, "사진 선택 갤러리 (시연 목업)", Toast.LENGTH_SHORT).show());

        // 하단 버튼 클릭 (시작하기 / 변경하기)
        btnSubmit.setOnClickListener(v -> saveProfileAndFinish());
    }

    private void updateCharCounters() {
        int nickLen = etNickname.getText() != null ? etNickname.getText().length() : 0;
        tvNicknameCount.setText(nickLen + "/10");

        int bioLen = etBio.getText() != null ? etBio.getText().length() : 0;
        tvBioCount.setText(bioLen + "/150");
    }

    private void saveProfileAndFinish() {
        String nickname = etNickname.getText().toString().trim();
        String department = etDepartment.getText().toString().trim();

        // 필수값(닉네임·학과)이 비어 있으면 저장·화면 이동을 하지 않고 안내만 표시한다.
        ProfileValidator.Error nicknameError = ProfileValidator.validateNickname(nickname);
        ProfileValidator.Error departmentError = ProfileValidator.validateDepartment(department);
        showFieldError(tvNicknameError, nicknameError);
        showFieldError(tvDepartmentError, departmentError);
        if (nicknameError != null || departmentError != null) {
            (nicknameError != null ? etNickname : etDepartment).requestFocus();
            Toast.makeText(this, R.string.profile_error_check_required, Toast.LENGTH_SHORT).show();
            return;
        }

        UserProfile draft = profile.copy();
        draft.setNickname(nickname);
        draft.setDepartment(department);
        draft.setGrade(spinnerGrade.getSelectedItem().toString());
        if (mode == MODE_CREATE) {
            draft.setGender(spinnerGender.getSelectedItem().toString());
        }
        draft.setBio(etBio.getText().toString().trim());
        draft.setInterestTags(new ArrayList<>(selectedTags));

        btnSubmit.setEnabled(false); // 중복 저장 방지
        profileRepo.saveProfile(draft, new ProfileRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (isFinishing() || isDestroyed()) return;
                profile = draft;
                if (mode == MODE_CREATE) {
                    Toast.makeText(ProfileSetupActivity.this, "프로필이 생성되었습니다!", Toast.LENGTH_SHORT).show();
                    // 온보딩·인증 화면으로 뒤로 돌아가지 않도록 태스크를 새로 시작한다.
                    Intent intent = new Intent(ProfileSetupActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    Toast.makeText(ProfileSetupActivity.this, "프로필이 수정되었습니다!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                }
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                btnSubmit.setEnabled(true);
                Toast.makeText(ProfileSetupActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showFieldError(TextView errorView, ProfileValidator.Error error) {
        if (error == null) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(error.getMessage());
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private void handleBackAction() {
        if (mode == MODE_EDIT) {
            showCancelConfirmDialog();
        } else {
            finish();
        }
    }

    private void showCancelConfirmDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_profile_cancel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        dialog.findViewById(R.id.btnCancelClose).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnCancelConfirm).setOnClickListener(v -> {
            dialog.dismiss();
            finish();
        });

        dialog.show();
    }
}
