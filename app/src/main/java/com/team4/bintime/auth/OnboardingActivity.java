package com.team4.bintime.auth;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;

/**
 * 앱 최초 진입 온보딩 화면 (Figma 41:9992).
 * (BT-003 / Issue #3)
 */
public class OnboardingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            Intent intent = new Intent(OnboardingActivity.this, EmailAuthActivity.class);
            startActivity(intent);
        });
    }
}
