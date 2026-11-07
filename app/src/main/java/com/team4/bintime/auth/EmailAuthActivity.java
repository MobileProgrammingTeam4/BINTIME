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

import com.team4.bintime.MainActivity;
import com.team4.bintime.R;

/**
 * 학교 이메일 링크 인증 화면 (Figma 100:1411, 235:1961, 268:1969, 268:2004, 268:2039).
 * (BT-003 / Issue #3)
 *
 * Firebase Auth 이메일 링크 인증 방식에 맞추어 6자리 OTP 입력창 없이
 * 링크 발송 안내, 앱 복귀 대기, 재전송 및 링크 만료/오류 예외 흐름을 제공합니다.
 */
public class EmailAuthActivity extends AppCompatActivity {

    private MockAuthRepository authRepository = new MockAuthRepository();
    private EmailVerificationState authState = new EmailVerificationState();

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
                    (EmailVerificationState) savedInstanceState.getSerializable("authState");
            if (restoredState != null) authState = restoredState;

            MockAuthRepository restoredRepo =
                    (MockAuthRepository) savedInstanceState.getSerializable("authRepo");
            if (restoredRepo != null) authRepository = restoredRepo;
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
        outState.putSerializable("authState", authState);
        outState.putSerializable("authRepo", authRepository);
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

        if (authState.getEmail() != null && !authState.getEmail().isEmpty()) {
            etEmail.setText(authState.getEmail());
        }
    }

    private void bindEvents() {
        // 실시간 이메일 도메인 유효성 검증
        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int count, int after) {
                authState.setEmail(s != null ? s.toString().trim() : "");
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
                authState.setEmail("");
                updateUiForState(EmailVerificationState.Status.INPUT);
            } else {
                updateUiForState(EmailVerificationState.Status.INPUT);
            }
        });

        // 목업 시연용: 링크 클릭 완료 시뮬레이션
        btnSimulateLinkClick.setOnClickListener(v -> onVerificationLinkOpened(authState.getEmail()));

        // 목업 테스트용 상태 전환 칩
        findViewById(R.id.btnSimulateExpired).setOnClickListener(v ->
                updateUiForState(EmailVerificationState.Status.EXPIRED));
        findViewById(R.id.btnSimulateInvalid).setOnClickListener(v ->
                updateUiForState(EmailVerificationState.Status.INVALID_LINK));
        findViewById(R.id.btnSimulateOtherDevice).setOnClickListener(v ->
                updateUiForState(EmailVerificationState.Status.DIFFERENT_DEVICE));
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
            tvEmailHelper.setVisibility(View.VISIBLE);
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
            tvEmailHelper.setVisibility(View.VISIBLE);
            return;
        }

        onVerificationLinkOpened(email);
    }

    /**
     * 사용자가 수신된 이메일 링크를 클릭하여 앱으로 복귀했을 때의 콜백 처리.
     */
    private void onVerificationLinkOpened(String email) {
        authRepository.verifySignInLink(email, "mock_dynamic_link", new AuthRepository.Callback<String>() {
            @Override
            public void onSuccess(String uid) {
                authState.setStatus(EmailVerificationState.Status.VERIFIED);
                Toast.makeText(EmailAuthActivity.this, "학생 인증이 완료되었습니다!", Toast.LENGTH_SHORT).show();

                // 인증 완료 후 다음 화면으로 이동
                // BT-004 프로필 설정 화면 또는 메인 화면 연결
                navigateToNextScreen(email);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(EmailAuthActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void navigateToNextScreen(String email) {
        // BT-004의 ProfileSetupActivity로 이동하거나 MainActivity로 이동
        try {
            Class<?> profileActivityClass = Class.forName("com.team4.bintime.profile.ProfileSetupActivity");
            Intent intent = new Intent(EmailAuthActivity.this, profileActivityClass);
            intent.putExtra("userEmail", email);
            startActivity(intent);
            finish();
        } catch (ClassNotFoundException e) {
            // 프로필 액티비티가 아직 없는 경우 MainActivity로 안전하게 이동
            Intent intent = new Intent(EmailAuthActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }
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
