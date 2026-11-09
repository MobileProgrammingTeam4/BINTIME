package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.profile.ProfileSetupActivity;

import java.util.Collections;

public class TimetableSelectModeActivity extends AppCompatActivity {
    private static final String EXTRA_SIGNUP_EMAIL = "timetable_signup_email";

    private final ActivityResultLauncher<Intent> registration = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                TimetableDraft draft = TimetableDraft.read(result.getData());
                if (result.getResultCode() != RESULT_OK || draft == null) return;
                MockTimetableRepository.save(draft);
                String email = getIntent().getStringExtra(EXTRA_SIGNUP_EMAIL);
                if (email != null) {
                    Intent profile = new Intent(this, ProfileSetupActivity.class);
                    profile.putExtra(ProfileSetupActivity.EXTRA_USER_EMAIL, email);
                    startActivity(profile);
                } else {
                    setResult(RESULT_OK);
                }
                finish();
            });

    public static Intent forSignup(Context context, String email) {
        return new Intent(context, TimetableSelectModeActivity.class).putExtra(EXTRA_SIGNUP_EMAIL, email);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timetable_select_mode);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnManualInput).setOnClickListener(v -> {
            TimetableDraft draft = MockTimetableRepository.load();
            if (draft == null) {
                draft = new TimetableDraft(Collections.singletonList(
                        new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30")));
            }
            registration.launch(TimetableDraft.open(this, CourseDirectInputActivity.class, draft));
        });
        findViewById(R.id.btnImageUpload).setOnClickListener(v ->
                registration.launch(new Intent(this, TimetableUploadActivity.class)));
    }
}
