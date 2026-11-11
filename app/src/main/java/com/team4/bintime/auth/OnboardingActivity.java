package com.team4.bintime.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.timetable.TimetableSelectModeActivity;

/**
 * 앱 최초 진입 온보딩 화면 (Figma 41:9992).
 * (BT-003 / Issue #3, BT-013 / Issue #13, BT-014 / Issue #14)
 * 이미 로그인된 사용자는 로그인 상태를 복원해, 프로필이 있으면 메인으로, 없으면 가입 단계로 보낸다.
 * 프로필 확인에 실패하면(네트워크·권한 오류) 완료 여부를 알 수 없으므로 메인으로 보내지 않고
 * 같은 버튼을 "다시 시도"로 바꿔 재확인하게 한다.
 */
public class OnboardingActivity extends AppCompatActivity {

    private TextView btnStart;
    /** 로그인된 계정의 프로필 확인에 실패해 버튼이 "다시 시도" 역할을 하는 상태. */
    private boolean retryMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            AuthRepository auth = AuthRepositories.get(this);
            if (retryMode && auth.isSignedIn()) {
                routeSignedInUser(auth);
                return;
            }
            Intent intent = new Intent(OnboardingActivity.this, EmailAuthActivity.class);
            startActivity(intent);
        });

        AuthRepository auth = AuthRepositories.get(this);
        if (auth.isSignedIn()) routeSignedInUser(auth);
    }

    private void routeSignedInUser(AuthRepository auth) {
        // 프로필 확인 중에는 버튼을 누르지 못하게 한다(중복 요청 방지).
        btnStart.setEnabled(false);
        ProfileRepositories.get(this).hasCompletedProfile(auth.getCurrentUid(), new ProfileRepository.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean completed) {
                if (isFinishing() || isDestroyed()) return;
                Intent intent = Boolean.TRUE.equals(completed)
                        ? new Intent(OnboardingActivity.this, MainActivity.class)
                        : TimetableSelectModeActivity.forSignup(OnboardingActivity.this, auth.getCurrentEmail());
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                // 미완성 계정이 메인으로 들어가지 않도록 이 화면에 머물고 재시도할 수 있게 한다.
                Toast.makeText(OnboardingActivity.this, message, Toast.LENGTH_SHORT).show();
                retryMode = true;
                btnStart.setText(R.string.onboarding_retry_button);
                btnStart.setEnabled(true);
            }
        });
    }
}
