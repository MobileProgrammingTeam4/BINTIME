package com.team4.bintime.auth;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.profile.ProfileSetupActivity;

/**
 * 학교 이메일 링크 인증 화면 (Figma 100:1411, 235:1961, 268:1969, 268:2004, 268:2039).
 * (BT-003 / Issue #3)
 *
 * Firebase Auth 이메일 링크 인증 방식에 맞추어 6자리 OTP 입력창 없이
 * 링크 발송 안내, 앱 복귀 대기, 재전송 및 링크 만료/오류 예외 흐름을 제공합니다.
 *
 * 화면 상태(만료·잘못된 링크·이메일 재입력)는 시연 버튼이 직접 바꾸지 않고,
 * {@link MockAuthRepository#verifySignInLink} 의 실패 사유에 따라 전환됩니다.
 */
public class EmailAuthActivity extends AppCompatActivity {

    private static final String STATE_AUTH = "authState";
    private static final String STATE_OTHER_DEVICE_LINK = "otherDeviceLink";
    /** 시연용: 발송한 적 없는 형식의 링크. */
    private static final String DEMO_INVALID_LINK = MockAuthRepository.LINK_PREFIX + "invalid";

    private final MockAuthRepository authRepository = MockAuthRepository.shared();
    /** status 와 "인증 링크를 보낸 이메일" 을 보관한다. 입력창 내용은 EditText 가 직접 복원한다. */
    private EmailVerificationState authState = new EmailVerificationState();
    /** 다른 기기에서 연 링크. 이 기기에는 요청 이메일 정보가 없으므로 사용자가 이메일을 다시 입력한다. */
    private String otherDeviceLink;

    private TextView tvMainTitle;
    private TextView tvSubTitle;
    private LinearLayout layoutEmailInputSection;
    private EditText etEmail;
    private TextView tvEmailHelper;

    private LinearLayout layoutSentInfoSection;
    private TextView tvSentEmailDisplay;
    private AppCompatButton btnSimulateLinkClick;

    private TextView tvSecondaryAction;
    private AppCompatButton btnPrimaryAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_auth);

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
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(STATE_AUTH, authState);
        outState.putString(STATE_OTHER_DEVICE_LINK, otherDeviceLink);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> handleBack());

        tvMainTitle = findViewById(R.id.tvMainTitle);
        tvSubTitle = findViewById(R.id.tvSubTitle);
        layoutEmailInputSection = findViewById(R.id.layoutEmailInputSection);
        etEmail = findViewById(R.id.etEmail);
        tvEmailHelper = findViewById(R.id.tvEmailHelper);

        layoutSentInfoSection = findViewById(R.id.layoutSentInfoSection);
        tvSentEmailDisplay = findViewById(R.id.tvSentEmailDisplay);
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

        // 목업 시연용: 받은 메일의 링크를 이 기기에서 연 경우 (정상 인증)
        btnSimulateLinkClick.setOnClickListener(v ->
                onVerificationLinkOpened(authState.getEmail(), authRepository.getIssuedLinkForDemo()));

        // 목업 시연용 예외: 실제로 저장소 검증을 거쳐 실패 사유에 맞는 화면으로 전환된다.
        findViewById(R.id.btnSimulateExpired).setOnClickListener(v -> {
            String link = authRepository.getIssuedLinkForDemo();
            authRepository.expirePendingLinkForDemo();
            onVerificationLinkOpened(authState.getEmail(), link);
        });
        findViewById(R.id.btnSimulateInvalid).setOnClickListener(v ->
                onVerificationLinkOpened(authState.getEmail(), DEMO_INVALID_LINK));
        findViewById(R.id.btnSimulateOtherDevice).setOnClickListener(v -> {
            // 다른 기기에는 요청 이메일이 저장되어 있지 않으므로 링크만 가진 채 이메일을 다시 입력받는다.
            otherDeviceLink = authRepository.getIssuedLinkForDemo();
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
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_primary);
            btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.bg_card));
        } else {
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_disabled);
            btnPrimaryAction.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        }
    }

    private void sendEmailLink() {
        String email = etEmail.getText().toString().trim();
        if (!EmailVerificationState.isValidUniversityEmail(email)) {
            showEmailHelper(getString(R.string.auth_error_invalid_domain));
            return;
        }

        authRepository.sendSignInLink(email, new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                authState.setStatus(EmailVerificationState.Status.SENT);
                authState.setEmail(email);
                updateUiForState(EmailVerificationState.Status.SENT);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void resendEmailLink() {
        String email = authState.getEmail();
        if (email.isEmpty()) {
            email = etEmail.getText().toString().trim();
        }

        authRepository.resendSignInLink(email, new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                Toast.makeText(EmailAuthActivity.this, R.string.auth_resend_success, Toast.LENGTH_SHORT).show();
                updateUiForState(EmailVerificationState.Status.SENT);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
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
     * 저장소 검증을 통과해야만 프로필 생성 화면으로 이동한다.
     */
    private void onVerificationLinkOpened(String email, String link) {
        authRepository.verifySignInLink(email, link, new AuthRepository.Callback<String>() {
            @Override
            public void onSuccess(String uid) {
                if (isFinishing() || isDestroyed()) return;
                authState.setStatus(EmailVerificationState.Status.VERIFIED);
                otherDeviceLink = null;
                Toast.makeText(EmailAuthActivity.this, "학생 인증이 완료되었습니다!", Toast.LENGTH_SHORT).show();
                navigateToProfileSetup(email);
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(AuthRepository.Failure failure, String message) {
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
                // 이메일 재입력 화면에 머무르며 입력값 오류를 안내한다.
                showEmailHelper(message);
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

    private void navigateToProfileSetup(String email) {
        Intent intent = new Intent(EmailAuthActivity.this, ProfileSetupActivity.class);
        intent.putExtra(ProfileSetupActivity.EXTRA_MODE, ProfileSetupActivity.MODE_CREATE);
        intent.putExtra(ProfileSetupActivity.EXTRA_USER_EMAIL, email);
        startActivity(intent);
        finish();
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
                layoutSentInfoSection.setVisibility(View.VISIBLE);
                tvSentEmailDisplay.setText(authState.getEmail());

                tvSecondaryAction.setVisibility(View.VISIBLE);
                tvSecondaryAction.setText(R.string.auth_reenter_email);

                btnPrimaryAction.setText(R.string.auth_resend_link_button);
                btnPrimaryAction.setEnabled(true);
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_outline);
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
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_outline);
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
                btnPrimaryAction.setBackgroundResource(R.drawable.bg_btn_outline);
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
    }

    private void handleBack() {
        if (authState.getStatus() != EmailVerificationState.Status.INPUT) {
            updateUiForState(EmailVerificationState.Status.INPUT);
        } else {
            finish();
        }
    }
}
