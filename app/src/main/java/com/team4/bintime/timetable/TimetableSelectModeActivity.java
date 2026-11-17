package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;
import com.team4.bintime.profile.ProfileSetupActivity;

import java.util.Collections;

/**
 * 시간표 등록 방식 선택(직접 입력 / 이미지). [BT-015] 저장은 이후 {@link TimetableRegistrationActivity}의
 * 등록 완료에서 끝나므로 여기서는 결과만 받아 다음 화면으로 넘어간다.
 */
public class TimetableSelectModeActivity extends AppCompatActivity {
    private static final String EXTRA_SIGNUP_EMAIL = "timetable_signup_email";
    private AsyncTimetableRepository.Registration loading;
    /** Mock은 콜백을 즉시 부르므로 Registration 대신 별도 플래그로 중복 클릭을 막는다. */
    private boolean loadingDraft;

    private final ActivityResultLauncher<Intent> registration = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                TimetableDraft draft = TimetableDraft.read(result.getData());
                if (result.getResultCode() != RESULT_OK || draft == null) return;
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
            if (!getIntent().hasExtra(EXTRA_SIGNUP_EMAIL)) {
                // 새로 등록: 저장하면 기존 수업이 바뀌므로 등록 화면에서 교체 확인을 받는다(BT-026 결정 0-3).
                registration.launch(TimetableDraft.open(this, CourseDirectInputActivity.class,
                        new TimetableDraft(Collections.emptyList()).markReplacesExisting()));
                return;
            }
            // 가입 중: 이미 저장한 시간표가 있으면 이어서 수정, 없으면 예시 수업 하나로 시작한다.
            if (loadingDraft) return;
            loadingDraft = true;
            setManualInputEnabled(false);
            loading = TimetableLoader.loadOnce(TimetableRepositories.get(this),
                    new AsyncTimetableRepository.Callback<Timetable>() {
                        @Override
                        public void onSuccess(Timetable timetable) {
                            loadingDraft = false;
                            if (isFinishing() || isDestroyed()) return;
                            setManualInputEnabled(true);
                            TimetableDraft draft = timetable == null || timetable.getCourseCount() == 0
                                    ? new TimetableDraft(Collections.singletonList(
                                            new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30")))
                                            .markReplacesExisting()
                                    : TimetableDraftMapper.toDraft(timetable);
                            registration.launch(TimetableDraft.open(TimetableSelectModeActivity.this,
                                    CourseDirectInputActivity.class, draft));
                        }

                        @Override
                        public void onError(TimetableFailure failure) {
                            loadingDraft = false;
                            if (isFinishing() || isDestroyed()) return;
                            setManualInputEnabled(true);
                            Toast.makeText(TimetableSelectModeActivity.this, TimetableMessages.LOAD_FAILED,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        });
        findViewById(R.id.btnImageUpload).setOnClickListener(v ->
                registration.launch(new Intent(this, TimetableUploadActivity.class)));
    }

    private void setManualInputEnabled(boolean enabled) {
        View manual = findViewById(R.id.btnManualInput);
        manual.setEnabled(enabled);
        manual.setAlpha(enabled ? 1f : 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (loading != null) loading.remove();
        super.onDestroy();
    }
}
