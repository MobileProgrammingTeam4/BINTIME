package com.team4.bintime.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.MainActivity;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.timetable.TimetableSelectModeActivity;

/**
 * 학교 이메일 링크 인증 화면 (Figma 100:1411, 235:1961, 268:1969, 268:2004, 268:2039).
 * (BT-003 / Issue #3, BT-013 / Issue #13)
 *
 * Firebase Auth 이메일 링크 인증 방식에 맞추어 6자리 OTP 입력창 없이
 * 링크 발송 안내, 앱 복귀 대기, 재전송 및 링크 만료/오류 예외 흐름을 제공합니다.
 *
 * google-services.json 이 있으면 실제 Firebase 링크가 App Link 로 이 화면에 돌아오고,
 * 없으면 Mock 저장소와 시연 버튼으로 같은 흐름을 재현한다.
 * 화면 상태(만료·잘못된 링크·이메일 재입력)는 저장소 검증의 실패 사유에 따라 전환됩니다.
 */
public class EmailAuthActivity extends AppCompatActivity {

    private static final String STATE_AUTH = "authState";
    private static final String STATE_OTHER_DEVICE_LINK = "otherDeviceLink";
    /** 시연용: 발송한 적 없는 형식의 링크. */
    private static final String DEMO_INVALID_LINK = MockAuthRepository.LINK_PREFIX + "invalid";

    private AuthRepository authRepository;
    /** Mock 모드에서만 존재. 시연 버튼이 발급 링크를 꺼내는 데 쓴다. */
    private MockAuthRepository mockRepository;
    /** status 와 "인증 링크를 보낸 이메일" 을 보관한다. 입력창 내용은 EditText 가 직접 복원한다. */
    private EmailVerificationState authState = new EmailVerificationState();
    /** 다른 기기에서 연 링크. 이 기기에는 요청 이메일 정보가 없으므로 사용자가 이메일을 다시 입력한다. */
    private String otherDeviceLink;
    /** 발송·검증 요청 중 중복 클릭 방지. */
    private boolean requestInFlight;

    private TextView tvMainTitle;
    private TextView tvSubTitle;
    private LinearLayout layoutEmailInputSection;
    private EditText etEmail;
    private TextView tvEmailHelper;

    /** 목업 시연 버튼 영역. Figma 에 없는 영역이며 Mock 모드에서 링크 발송 후에만 보인다. */
    private LinearLayout layoutSentInfoSection;
    private TextView btnSimulateLinkClick;

    private TextView tvSecondaryAction;
    private TextView btnPrimaryAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_auth);

        authRepository = AuthRepositories.get(this);
        if (authRepository instanceof MockAuthRepository) {
            mockRepository = (MockAuthRepository) authRepository;
        }

        if (savedInstanceState != null) {
            EmailVerificationState restoredState =
                    (EmailVerificationState) savedInstanceState.getSerializable(STATE_AUTH);
            if (restoredState != null) authState = restoredState;
            otherDeviceLink = savedInstanceState.getString(STATE_OTHER_DEVICE_LINK);
        }

        initViews();
        bindEvents();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
        updateUiForState(authState.getStatus());

        // 메일의 링크로 앱이 처음 열린 경우. 회전 등으로 재생성된 경우는 이미 처리했으므로 건너뛴다.
        if (savedInstanceState == null) handleIncomingLink(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingLink(intent);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(STATE_AUTH, authState);
        outState.putString(STATE_OTHER_DEVICE_LINK, otherDeviceLink);
    }

    /**
     * 메일 링크(App Link)로 돌아온 경우 처리.
     * 같은 기기에서 요청했다면 저장된 이메일로 바로 인증하고, 아니면 이메일 재입력 화면을 보여 준다.
     */
    private void handleIncomingLink(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String link = data.toString();
        if (!authRepository.isSignInLink(link)) {
            updateUiForState(EmailVerificationState.Status.INVALID_LINK);
            return;
        }

        String pendingEmail = authRepository.getPendingEmail();
        if (pendingEmail != null && !pendingEmail.isEmpty()) {
            authState.setEmail(pendingEmail);
            onVerificationLinkOpened(pendingEmail, link);
        } else {
            otherDeviceLink = link;
            etEmail.setText("");
            updateUiForState(EmailVerificationState.Status.DIFFERENT_DEVICE);
        }
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> handleBack());

        tvMainTitle = findViewById(R.id.tvMainTitle);
        tvSubTitle = findViewById(R.id.tvSubTitle);
        layoutEmailInputSection = findViewById(R.id.layoutEmailInputSection);
        etEmail = findViewById(R.id.etEmail);
        tvEmailHelper = findViewById(R.id.tvEmailHelper);

        layoutSentInfoSection = findViewById(R.id.layoutSentInfoSection);
        btnSimulateLinkClick = findViewById(R.id.btnSimulateLinkClick);

        tvSecondaryAction = findViewById(R.id.tvSecondaryAction);
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction);
    }

    private void bindEvents() {
        // 실시간 이메일 도메인 유효성 검증
        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                validateInputEmail();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 메인 액션 버튼 클릭
        btnPrimaryAction.setOnClickListener(v -> {
            if (requestInFlight) return;
            switch (authState.getStatus()) {
                case INPUT:
                    sendEmailLink();
                    break;
                case SENT:
                case EXPIRED:
                case INVALID_LINK:
                    resendEmailLink();
                    break;
                case DIFFERENT_DEVICE:
                    completeDifferentDeviceAuth();
                    break;
                default:
                    break;
            }
        });

        // 보조 액션 버튼 클릭 (이메일 다시 입력하기 등)
        tvSecondaryAction.setOnClickListener(v -> {
            if (authState.getStatus() == EmailVerificationState.Status.DIFFERENT_DEVICE) {
                etEmail.setText("");
                otherDeviceLink = null;
            }
            updateUiForState(EmailVerificationState.Status.INPUT);
        });

        if (mockRepository == null) return;

        // 목업 시연용: 받은 메일의 링크를 이 기기에서 연 경우 (정상 인증)
        btnSimulateLinkClick.setOnClickListener(v ->
                onVerificationLinkOpened(authState.getEmail(), mockRepository.getIssuedLinkForDemo()));

        // 목업 시연용 예외: 실제로 저장소 검증을 거쳐 실패 사유에 맞는 화면으로 전환된다.
        findViewById(R.id.btnSimulateExpired).setOnClickListener(v -> {
            String link = mockRepository.getIssuedLinkForDemo();
            mockRepository.expirePendingLinkForDemo();
            onVerificationLinkOpened(authState.getEmail(), link);
        });
        findViewById(R.id.btnSimulateInvalid).setOnClickListener(v ->
                onVerificationLinkOpened(authState.getEmail(), DEMO_INVALID_LINK));
        findViewById(R.id.btnSimulateOtherDevice).setOnClickListener(v -> {
            // 다른 기기에는 요청 이메일이 저장되어 있지 않으므로 링크만 가진 채 이메일을 다시 입력받는다.
            otherDeviceLink = mockRepository.getIssuedLinkForDemo();
            etEmail.setText("");
            updateUiForState(EmailVerificationState.Status.DIFFERENT_DEVICE);
        });
    }

    private void validateInputEmail() {
        if (authState.getStatus() != EmailVerificationState.Status.INPUT &&
                authState.getStatus() != EmailVerificationState.Status.DIFFERENT_DEVICE) {
            return;
        }

        String input = etEmail.getText().toString().trim();
        boolean isValid = EmailVerificationState.isValidUniversityEmail(input);

        if (input.isEmpty()) {
            tvEmailHelper.setVisibility(View.GONE);
            setPrimaryButtonEnabled(false);
        } else if (!isValid) {
            if (input.contains("@")) {
                tvEmailHelper.setText(R.string.auth_error_invalid_domain);
                tvEmailHelper.setVisibility(View.VISIBLE);
            } else {
                tvEmailHelper.setVisibility(View.GONE);
            }
            setPrimaryButtonEnabled(false);
        } else {
            tvEmailHelper.setVisibility(View.GONE);
            setPrimaryButtonEnabled(true);
        }
    }

    private void setPrimaryButtonEnabled(boolean enabled) {
        btnPrimaryAction.setEnabled(enabled);
        if (enabled) {
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_pill_primary);
            btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.bg_card));
        } else {
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_disabled);
            btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.form_text_disabled));
        }
    }

    private void sendEmailLink() {
        String email = etEmail.getText().toString().trim();
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            showEmailHelper(getString(R.string.auth_error_invalid_domain));
            return;
        }
        // 같은 이메일로 방금 보냈다면 재발송 제한을 그대로 적용한다.
        if (email.equalsIgnoreCase(authState.getEmail()) && isResendCoolingDown()) return;

        requestInFlight = true;
        authRepository.sendSignInLink(email, new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                authState.setEmail(email);
                authState.setSentTimestamp(System.currentTimeMillis());
                updateUiForState(EmailVerificationState.Status.SENT);
            }

            @Override
            public void onError(String message) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void resendEmailLink() {
        String email = authState.getEmail();
        if (email.isEmpty()) {
            email = etEmail.getText().toString().trim();
        }
        if (isResendCoolingDown()) return;

        requestInFlight = true;
        final String target = email;
        authRepository.resendSignInLink(target, new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                authState.setEmail(target);
                authState.setSentTimestamp(System.currentTimeMillis());
                Toast.makeText(EmailAuthActivity.this, R.string.auth_resend_success, Toast.LENGTH_SHORT).show();
                updateUiForState(EmailVerificationState.Status.SENT);
            }

            @Override
            public void onError(String message) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 재발송 쿨다운 중이면 남은 시간을 안내하고 true. */
    private boolean isResendCoolingDown() {
        int seconds = authState.remainingResendCooldownSeconds(System.currentTimeMillis());
        if (seconds <= 0) return false;
        Toast.makeText(this, getString(R.string.auth_resend_cooldown, seconds), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void completeDifferentDeviceAuth() {
        String email = etEmail.getText().toString().trim();
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            showEmailHelper(getString(R.string.auth_error_invalid_domain));
            return;
        }

        onVerificationLinkOpened(email, otherDeviceLink);
    }

    /**
     * 사용자가 수신된 이메일 링크를 클릭하여 앱으로 복귀했을 때의 콜백 처리.
     * 저장소 검증을 통과해야만 첫 가입의 시간표 등록 또는 기존 계정의 메인 화면으로 이동한다.
     */
    private void onVerificationLinkOpened(String email, String link) {
        requestInFlight = true;
        authRepository.verifySignInLink(email, link, new AuthRepository.Callback<String>() {
            @Override
            public void onSuccess(String uid) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                authState.setStatus(EmailVerificationState.Status.VERIFIED);
                otherDeviceLink = null;
                Toast.makeText(EmailAuthActivity.this, "학생 인증이 완료되었습니다!", Toast.LENGTH_SHORT).show();
                navigateAfterVerification(uid, email);
            }

            @Override
            public void onError(String message) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(AuthRepository.Failure failure, String message) {
                requestInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                handleVerificationFailure(failure, message);
            }
        });
    }

    private void handleVerificationFailure(AuthRepository.Failure failure, String message) {
        switch (failure) {
            case LINK_EXPIRED:
                updateUiForState(EmailVerificationState.Status.EXPIRED);
                break;
            case INVALID_LINK:
            case LINK_ALREADY_USED:
                updateUiForState(EmailVerificationState.Status.INVALID_LINK);
                break;
            case EMAIL_MISMATCH:
            case INVALID_EMAIL:
                if (authState.getStatus() == EmailVerificationState.Status.DIFFERENT_DEVICE) {
                    // 이메일 재입력 화면에 머무르며 입력값 오류를 안내한다.
                    showEmailHelper(message);
                } else {
                    // 저장된 이메일로 자동 인증하다 실패한 경우: 이메일을 직접 입력받는다.
                    if (otherDeviceLink == null) otherDeviceLink = getIntent() != null && getIntent().getData() != null
                            ? getIntent().getData().toString() : null;
                    etEmail.setText("");
                    updateUiForState(EmailVerificationState.Status.DIFFERENT_DEVICE);
                    showEmailHelper(message);
                }
                break;
            case NETWORK:
            case TOO_MANY_REQUESTS:
            case UNKNOWN:
                // 링크는 그대로 유효할 수 있으므로 현재 화면에 머무르고 다시 시도하게 한다.
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                break;
            case LINK_NOT_SENT:
            default:
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                updateUiForState(EmailVerificationState.Status.INPUT);
                break;
        }
    }

    private void showEmailHelper(String message) {
        tvEmailHelper.setText(message);
        tvEmailHelper.setVisibility(View.VISIBLE);
    }

    private void navigateAfterVerification(String uid, String email) {
        // BT-014: 프로필을 만든 계정은 메인으로, 아니면 가입 단계(시간표 → 프로필)로 보낸다.
        ProfileRepositories.get(this).hasCompletedProfile(uid, new ProfileRepository.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean completed) {
                if (isFinishing() || isDestroyed()) return;
                Intent intent = Boolean.TRUE.equals(completed)
                        ? new Intent(EmailAuthActivity.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        : TimetableSelectModeActivity.forSignup(EmailAuthActivity.this, email);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                // 로그인은 끝났으므로 온보딩이 프로필 확인을 다시 시도한다.
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(EmailAuthActivity.this, OnboardingActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }
        });
    }

    /** 시연 버튼 영역은 Mock 모드의 발송 완료 화면에서만 보인다. */
    private int demoVisibility(boolean show) {
        return show && mockRepository != null ? View.VISIBLE : View.GONE;
    }

    private void updateUiForState(EmailVerificationState.Status status) {
        authState.setStatus(status);

        switch (status) {
            case INPUT:
                tvMainTitle.setText(R.string.auth_email_input_title);
                tvSubTitle.setText(R.string.auth_email_input_subtitle);
                layoutEmailInputSection.setVisibility(View.VISIBLE);
                layoutSentInfoSection.setVisibility(View.GONE);
                tvSecondaryAction.setVisibility(View.GONE);

                btnPrimaryAction.setText(R.string.auth_send_link_button);
                validateInputEmail();
                break;

            case SENT:
                tvMainTitle.setText(R.string.auth_check_email_title);
                tvSubTitle.setText(R.string.auth_check_email_subtitle);
                layoutEmailInputSection.setVisibility(View.GONE);
                layoutSentInfoSection.setVisibility(demoVisibility(true));

                tvSecondaryAction.setVisibility(View.VISIBLE);
                tvSecondaryAction.setText(R.string.auth_reenter_email);

                btnPrimaryAction.setText(R.string.auth_resend_link_button);
                btnPrimaryAction.setEnabled(true);
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_pill_outline);
                btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.action_primary));
                break;

            case EXPIRED:
                tvMainTitle.setText(R.string.auth_expired_title);
                tvSubTitle.setText(R.string.auth_expired_subtitle);
                layoutEmailInputSection.setVisibility(View.GONE);
                layoutSentInfoSection.setVisibility(View.GONE);

                tvSecondaryAction.setVisibility(View.VISIBLE);
                tvSecondaryAction.setText(R.string.auth_reenter_email);

                btnPrimaryAction.setText(R.string.auth_resend_link_button);
                btnPrimaryAction.setEnabled(true);
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_pill_outline);
                btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.action_primary));
                break;

            case INVALID_LINK:
                tvMainTitle.setText(R.string.auth_invalid_link_title);
                tvSubTitle.setText(R.string.auth_invalid_link_subtitle);
                layoutEmailInputSection.setVisibility(View.GONE);
                layoutSentInfoSection.setVisibility(View.GONE);

                tvSecondaryAction.setVisibility(View.VISIBLE);
                tvSecondaryAction.setText(R.string.auth_reenter_email);

                btnPrimaryAction.setText(R.string.auth_resend_link_button);
                btnPrimaryAction.setEnabled(true);
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_pill_outline);
                btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.action_primary));
                break;

            case DIFFERENT_DEVICE:
                tvMainTitle.setText(R.string.auth_different_device_title);
                tvSubTitle.setText(R.string.auth_different_device_subtitle);
                layoutEmailInputSection.setVisibility(View.VISIBLE);
                layoutSentInfoSection.setVisibility(View.GONE);

                tvSecondaryAction.setVisibility(View.VISIBLE);
                tvSecondaryAction.setText(R.string.auth_other_email_link);

                btnPrimaryAction.setText(R.string.auth_complete_button);
                validateInputEmail();
                break;

            default:
                break;
        }

        // 입력창이 없는 화면(발송 완료·만료·잘못된 링크)에서는 키보드를 내려 코드 입력 화면으로 오해하지 않게 한다.
        if (layoutEmailInputSection.getVisibility() != View.VISIBLE) hideKeyboard();
    }

    private void hideKeyboard() {
        etEmail.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etEmail.getWindowToken(), 0);
    }

    private void handleBack() {
        if (authState.getStatus() != EmailVerificationState.Status.INPUT) {
            updateUiForState(EmailVerificationState.Status.INPUT);
        } else {
            finish();
        }
    }
}
