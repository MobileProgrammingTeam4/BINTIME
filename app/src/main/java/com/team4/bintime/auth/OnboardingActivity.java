package com.team4.bintime.auth;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.MainActivity;
import com.team4.bintime.R;

/**
 * 앱 최초 진입 온보딩 화면 (Figma 41:9992).
 * (BT-003 / Issue #3, BT-013 / Issue #13)
 * 이미 이메일 링크로 로그인된 사용자는 로그인 상태를 복원해 바로 메인으로 보낸다.
 */
public class OnboardingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (AuthRepositories.get(this).isSignedIn()) {
            // 프로필 미완성 사용자의 가입 단계 복귀는 BT-014(Firestore 프로필 조회)에서 연결한다.
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            Intent intent = new Intent(OnboardingActivity.this, EmailAuthActivity.class);
            startActivity(intent);
        });
    }
}
