package com.team4.bintime.profile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
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
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

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
 * (BT-014 / Issue #14) 로그인 UID 의 프로필을 {@link ProfileRepositories#get} 저장소(Firestore 또는 Mock)에 저장하고,
 * 새로 고른 사진은 압축 후 Cloud Storage 에 올린다. 마이페이지와 같은 저장소라 저장 즉시 반영된다.
 * 화면 회전 시에는 저장소를 다시 읽지 않고 작성 중인 값(입력·선택 태그)을 그대로 복원한다.
 * 단, 수정 모드에서 기존 프로필 조회가 끝나기 전에 회전했다면 다시 조회한다. 조회가 끝나기 전에는
 * 빈 값으로 기존 프로필(소개·태그·사진·성별)을 덮어쓰지 않도록 저장을 막는다 (#79 리뷰 반영).
 * 저장(사진 압축·업로드·프로필 저장)은 {@link ProfileSaveViewModel} 이 진행해, 업로드 중 회전해도 저장이 누락되지 않는다.
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
    private static final String STATE_PHOTO_URI = "selectedPhotoUri";
    private static final String STATE_PROFILE_LOADED = "profileLoaded";
    private static final String KEY_CANCEL_EDIT = "profileCancelEdit";

    private int mode = MODE_CREATE;
    private ProfileRepository profileRepo;
    private UserProfile profile = UserProfile.empty();
    /** 저장 기준이 되는 프로필이 준비됐는지. 수정 모드는 원격 조회 성공 후에만 true. */
    private boolean profileLoaded;
    /** 기존 프로필 조회 중. 저장 버튼 연타로 조회가 중복되지 않게 한다. */
    private boolean profileLoading;
    private final ArrayList<String> selectedTags = new ArrayList<>();
    /** 이번 화면에서 새로 고른 사진. 저장할 때만 업로드한다. */
    private Uri selectedPhotoUri;
    private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;
    /** 저장 작업. 화면이 다시 만들어져도 유지된다. */
    private ProfileSaveViewModel saveViewModel;

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
        profileRepo = ProfileRepositories.get(this);
        saveViewModel = new ViewModelProvider(this).get(ProfileSaveViewModel.class);
        photoPicker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null) return; // 선택 취소
            selectedPhotoUri = uri;
            bindPhoto();
        });
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
            selectedPhotoUri = savedInstanceState.getParcelable(STATE_PHOTO_URI);
            // 조회 완료 전에 회전한 경우 복원값은 초기 빈 프로필이므로 복원으로 보지 않고 다시 조회한다.
            if (restoredProfile != null && savedInstanceState.getBoolean(STATE_PROFILE_LOADED)) {
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
            profileLoaded = true;
            bindPhoto();
            tvAiFeedback.setVisibility(savedInstanceState.getBoolean(STATE_AI_FEEDBACK) ? View.VISIBLE : View.GONE);
        } else if (mode == MODE_EDIT) {
            loadProfileData();
        } else {
            initCreateProfile();
        }
        setupTags();
        bindEvents();
        // 회전 전에 시작한 저장이 아직 진행 중이면 버튼을 막아 두고, 끝나면 결과를 받아 이동한다.
        btnSubmit.setEnabled(!saveViewModel.isSaving());
        saveViewModel.getResult().observe(this, this::onSaveResult);

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
        outState.putParcelable(STATE_PHOTO_URI, selectedPhotoUri);
        outState.putBoolean(STATE_PROFILE_LOADED, profileLoaded);
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
        if (profileLoading) return;
        profileLoading = true;
        profileLoaded = false;
        String uid = AuthRepositories.get(this).getCurrentUid();
        profileRepo.getProfile(uid, new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile user) {
                if (isFinishing() || isDestroyed()) return;
                profileLoading = false;
                profileLoaded = true;
                if (user == null) {
                    // 원격 문서가 아직 없으면 본인 UID 의 빈 프로필로 수정을 시작한다.
                    user = UserProfile.empty();
                    user.setUid(uid);
                    user.setEmail(AuthRepositories.get(ProfileSetupActivity.this).getCurrentEmail());
                }
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
                // 칩은 조회 전에 만들어졌을 수 있으므로 기존 태그가 선택 상태로 보이도록 다시 그린다.
                setupTags();

                updateCharCounters();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                profileLoading = false;
                Toast.makeText(ProfileSetupActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 최초 생성: 시연용 기본 프로필을 채우지 않고 인증된 계정 정보만 연결한 빈 폼으로 시작한다. */
    private void initCreateProfile() {
        profileLoaded = true;
        profile = UserProfile.empty();
        profile.setUid(AuthRepositories.get(this).getCurrentUid());
        // 인증된 계정 이메일을 우선 사용하고, Mock 모드에서는 가입 화면이 넘긴 값을 쓴다.
        String email = AuthRepositories.get(this).getCurrentEmail();
        profile.setEmail(email != null ? email : getIntent().getStringExtra(EXTRA_USER_EMAIL));
        selectedTags.clear();
        updateCharCounters();
    }

    /** 새로 고른 사진 → 저장된 Storage 사진 → 시연 리소스 순으로 표시한다. */
    private void bindPhoto() {
        if (selectedPhotoUri != null) {
            imgProfilePhoto.setImageURI(selectedPhotoUri);
        } else if (profile.getPhotoPath() != null && !profile.getPhotoPath().isEmpty()) {
            profileRepo.loadProfilePhoto(profile.getPhotoPath(), new ProfileRepository.Callback<Bitmap>() {
                @Override
                public void onSuccess(Bitmap bitmap) {
                    if (isFinishing() || isDestroyed() || selectedPhotoUri != null) return;
                    imgProfilePhoto.setImageBitmap(bitmap);
                }

                @Override
                public void onError(String message) {
                    // 사진을 못 불러와도 나머지 프로필 수정은 계속할 수 있다.
                }
            });
        } else if (profile.getPhotoResId() != 0) {
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
        // 시스템 사진 선택기: 저장소 권한 없이 사용자가 고른 사진 1장만 접근한다.
        findViewById(R.id.layoutPhotoContainer).setOnClickListener(v ->
                photoPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

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
        if (!profileLoaded) {
            // 기존 프로필을 아직 못 읽었다: 빈 값으로 덮어쓰지 않고 다시 불러온다.
            Toast.makeText(this, R.string.profile_not_loaded_retry, Toast.LENGTH_SHORT).show();
            loadProfileData();
            return;
        }
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

        if (saveViewModel.isSaving()) return;
        btnSubmit.setEnabled(false); // 중복 저장 방지
        // 새 사진이 있으면 압축 → Storage 업로드 → 경로를 프로필에 넣어 저장. 진행은 ViewModel 이 맡는다.
        saveViewModel.save(profileRepo, getApplicationContext().getContentResolver(), draft,
                selectedPhotoUri, getString(R.string.profile_photo_load_failed));
    }

    /** ViewModel 이 전달한 저장 결과. 회전 중에 끝난 결과도 새 Activity 가 한 번만 받는다. */
    private void onSaveResult(ProfileSaveViewModel.Result result) {
        if (result == null || !result.consume()) return;
        if (isFinishing() || isDestroyed()) return;
        if (!result.isSuccess()) {
            btnSubmit.setEnabled(true);
            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
            return;
        }
        profile = result.saved;
        selectedPhotoUri = null;
        if (mode == MODE_CREATE) {
            Toast.makeText(this, "프로필이 생성되었습니다!", Toast.LENGTH_SHORT).show();
            // 온보딩·인증 화면으로 뒤로 돌아가지 않도록 태스크를 새로 시작한다.
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        } else {
            Toast.makeText(this, "프로필이 수정되었습니다!", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        }
        finish();
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
