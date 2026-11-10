package com.team4.bintime.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
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
 */
public class OnboardingActivity extends AppCompatActivity {

    private View btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> {
            Intent intent = new Intent(OnboardingActivity.this, EmailAuthActivity.class);
            startActivity(intent);
        });

        AuthRepository auth = AuthRepositories.get(this);
        if (auth.isSignedIn()) routeSignedInUser(auth);
    }

    private void routeSignedInUser(AuthRepository auth) {
        // 프로필 확인 중에는 시작하기를 누르지 못하게 한다.
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
                // 네트워크 오류 등: 기존 동작대로 메인으로 보낸다. 메인 기능은 로그인만 확인한다.
                Toast.makeText(OnboardingActivity.this, message, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
                finish();
            }
        });
    }
}
