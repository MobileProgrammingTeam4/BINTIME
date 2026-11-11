package com.team4.bintime.profile;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.ChipGroup;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;

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
    private static final String STATE_AI_FEEDBACK = "aiFeedbackVisible";
    private static final String KEY_CANCEL_EDIT = "profileCancelEdit";

    private int mode = MODE_CREATE;
    private final MockProfileRepository profileRepo = MockProfileRepository.shared();
    private UserProfile profile = UserProfile.empty();
    private final ArrayList<String> selectedTags = new ArrayList<>();

    private ImageView imgProfilePhoto;
    private TextView tvPhotoLabel;
    private EditText etNickname;
    private TextView tvNicknameCount;
    private TextView tvNicknameError;
    private TextView tvDepartment;
    private ActivityResultLauncher<Intent> departmentLauncher;
    private TextView tvDepartmentError;
    private Spinner spinnerGender;
    private Spinner spinnerGrade;
    private TextView tvGenderNotice;
    private EditText etBio;
    private TextView tvBioCount;
    private TextView tvAiFeedback;
    private ChipGroup chipGroupAiTags;
    private ChipGroup chipGroupDefaultTags;
    private TextView btnSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);
        departmentLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String department = result.getData().getStringExtra(DepartmentSearchActivity.EXTRA_DEPARTMENT);
            if (department == null) return;
            tvDepartment.setText(department);
            tvDepartmentError.setVisibility(View.GONE);
        });
        // 확인 다이얼로그 결과는 화면 회전 뒤에도 받도록 매번 등록한다.
        getSupportFragmentManager().setFragmentResultListener(KEY_CANCEL_EDIT, this, (key, result) -> finish());

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
            tvAiFeedback.setVisibility(savedInstanceState.getBoolean(STATE_AI_FEEDBACK) ? View.VISIBLE : View.GONE);
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
        outState.putBoolean(STATE_AI_FEEDBACK, tvAiFeedback.getVisibility() == View.VISIBLE);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> handleBackAction());

        imgProfilePhoto = findViewById(R.id.imgProfilePhoto);
        tvPhotoLabel = findViewById(R.id.tvPhotoLabel);
        etNickname = findViewById(R.id.etNickname);
        tvNicknameCount = findViewById(R.id.tvNicknameCount);
        tvNicknameError = findViewById(R.id.tvNicknameError);
        tvDepartment = findViewById(R.id.tvDepartment);
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
            // 성별은 1회 선택 후 수정 불가 (Figma 102:2684: 회색 칸, 화살표 없음, 라벨·값 연회색)
            spinnerGender.setEnabled(false);
            findViewById(R.id.layoutGenderField).setBackgroundResource(R.drawable.bg_input_disabled);
            findViewById(R.id.imgGenderChevron).setVisibility(View.GONE);
            ((TextView) findViewById(R.id.tvGenderLabel))
                    .setTextColor(ContextCompat.getColor(this, R.color.form_text_disabled));
        } else {
            tvPhotoLabel.setText(R.string.profile_add_photo);
            btnSubmit.setText(R.string.profile_start_button);
            spinnerGender.setEnabled(true);
        }
    }

    private void setupSpinners() {
        spinnerGender.setAdapter(createSpinnerAdapter(new String[]{"여성", "남성"}, spinnerGender));
        spinnerGrade.setAdapter(createSpinnerAdapter(
                new String[]{"1학년", "2학년", "3학년", "4학년"}, spinnerGrade));
    }

    /** 선택값은 Figma 입력칸 모양(item_profile_spinner), 펼친 목록은 기본 항목. 비활성 칸은 연회색 글씨. */
    private ArrayAdapter<String> createSpinnerAdapter(String[] items, Spinner owner) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_profile_spinner, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(ContextCompat.getColor(getContext(),
                        owner.isEnabled() ? R.color.text_primary : R.color.form_text_disabled));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void loadProfileData() {
        profileRepo.getProfile("u1", new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile user) {
                profile = user;
                etNickname.setText(profile.getNickname());
                tvDepartment.setText(profile.getDepartment());
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
        profile.setUid(AuthRepositories.get(this).getCurrentUid());
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

    /** 공통 Figma Chip(Type=Interest). 선택 상태는 View.setSelected 로 표현한다. */
    private TextView createTagChip(String tag) {
        TextView chip = ChipViews.interest(this, "#" + tag, selectedTags.contains(tag));
        chip.setOnClickListener(v -> {
            if (!selectedTags.remove(tag)) selectedTags.add(tag);
            v.setSelected(selectedTags.contains(tag));
        });
        return chip;
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

        // 학과 칸 → 학과 검색 화면. 뒤로가기(선택 취소)면 기존 학과를 그대로 둔다.
        tvDepartment.setOnClickListener(v ->
                departmentLauncher.launch(new Intent(this, DepartmentSearchActivity.class)));

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
        String department = tvDepartment.getText().toString().trim();

        // 필수값(닉네임·학과)이 비어 있으면 저장·화면 이동을 하지 않고 안내만 표시한다.
        ProfileValidator.Error nicknameError = ProfileValidator.validateNickname(nickname);
        ProfileValidator.Error departmentError = ProfileValidator.validateDepartment(department);
        showFieldError(tvNicknameError, nicknameError);
        showFieldError(tvDepartmentError, departmentError);
        if (nicknameError != null || departmentError != null) {
            if (nicknameError != null) etNickname.requestFocus();
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

    /** 수정 취소 확인 (Figma 122:1959). 공통 ConfirmDialog 를 써서 화면 회전 후에도 유지된다. */
    private void showCancelConfirmDialog() {
        if (getSupportFragmentManager().findFragmentByTag(KEY_CANCEL_EDIT) != null) return;
        ConfirmDialog.show(getSupportFragmentManager(), KEY_CANCEL_EDIT,
                getString(R.string.profile_cancel_dialog_title),
                getString(R.string.profile_cancel_dialog_desc),
                getString(R.string.profile_cancel_dialog_confirm),
                getString(R.string.profile_cancel_dialog_close));
    }
}
