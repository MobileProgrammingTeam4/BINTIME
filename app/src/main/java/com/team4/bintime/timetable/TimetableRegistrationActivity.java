package com.team4.bintime.timetable;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.team4.bintime.R;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.FreeSlotValidator;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.Collections;

/**
 * 공강 선택 후 "다음"(등록 완료). [BT-015/016] 공강 선택이 확정되는 지점이므로 여기서 저장한다:
 * 수업 전체 교체(replaceCourses) → 공강 선택 저장(saveFreeSlots, availability 갱신).
 * 저장이 끝나야 RESULT_OK로 돌아가며, 실패하면 안내하고 이 화면에 남는다.
 * <p>
 * [BT-026 결정 0-3] 새 시간표 등록(이미지 인식·직접 입력 새로 시작, {@link TimetableDraft#replacesExisting()})은
 * replaceCourses 가 기존 수업을 모두 바꾸므로, 기존 수업이 있으면 저장 전에 "기존 수업이 새 시간표로 교체됩니다"
 * 확인을 받는다. 기존 수업 수를 읽지 못하면 수를 빼고 같은 확인을 띄운다.
 */
public class TimetableRegistrationActivity extends AppCompatActivity {
    private static final String CONFIRM_REPLACE = "timetable_confirm_replace";
    private static final String REPLACE_CONFIRMED = "timetable_replace_confirmed";
    private TimetableDraft draft;
    /** 교체 확인을 받았거나 기존 수업이 없음을 확인했다. */
    private boolean replaceConfirmed;
    private AsyncTimetableRepository.Registration checkingExisting;
    private TimetableSaveViewModel saver;
    private View complete;
    private OnBackPressedCallback blockBackWhileSaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_timetable_registration);
        draft = TimetableDraft.restore(state, getIntent());
        if (draft == null) draft = new TimetableDraft(Collections.emptyList());
        replaceConfirmed = state != null && state.getBoolean(REPLACE_CONFIRMED, false);
        draft.discardOccupiedFreeSlots();
        if (state == null) draft.suggestGaps();
        TimetableWeekView week = findViewById(R.id.timetable_week);
        week.bind(draft, () -> {});
        if (!draft.hasFreeTime()) {
            ((TextView) findViewById(R.id.timetable_hint)).setText(
                    "공강시간은 30분 단위로 선택할 수 있어요.");
        }
        complete = findViewById(R.id.btnCompleteRegistration);
        saver = new ViewModelProvider(this).get(TimetableSaveViewModel.class);
        blockBackWhileSaving = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                Toast.makeText(TimetableRegistrationActivity.this, TimetableMessages.SAVING,
                        Toast.LENGTH_SHORT).show();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, blockBackWhileSaving);
        saver.getResult().observe(this, result -> {
            if (result == null || !result.consume()) return;
            showSaving(false);
            if (result.isSuccess()) {
                setResult(RESULT_OK, draft.result());
                finish();
            } else {
                Toast.makeText(this, TimetableMessages.of(result.failure), Toast.LENGTH_LONG).show();
            }
        });
        showSaving(saver.isSaving());

        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        complete.setOnClickListener(v -> save());
        getSupportFragmentManager().setFragmentResultListener(CONFIRM_REPLACE, this, (key, result) -> {
            replaceConfirmed = true;
            save();
        });
    }

    private void save() {
        if (saver.isSaving()) return;
        draft.discardOccupiedFreeSlots();
        // 매칭 대상 공강 구간 상한(40개)은 저장 전에 안내하고 거부한다.
        if (FreeSlotValidator.checkLimit(draft.freeCells()) != null) {
            Toast.makeText(this, TimetableMessages.TOO_MANY_FREE_SLOTS, Toast.LENGTH_LONG).show();
            return;
        }
        if (draft.replacesExisting() && !replaceConfirmed) {
            confirmReplace();
            return;
        }
        showSaving(true);
        saver.save(TimetableRepositories.get(this),
                TimetableDraftMapper.toCourses(draft), TimetableDraftMapper.toFreeSlots(draft));
    }

    /** 기존 수업 수를 한 번 읽고, 있으면 교체 확인을 띄운다. 없으면 바로 저장한다. */
    private void confirmReplace() {
        if (checkingExisting != null) return;
        complete.setEnabled(false);
        checkingExisting = TimetableLoader.loadOnce(TimetableRepositories.get(this),
                new AsyncTimetableRepository.Callback<Timetable>() {
                    @Override
                    public void onSuccess(Timetable timetable) {
                        checkingExisting = null;
                        if (isFinishing() || isDestroyed()) return;
                        complete.setEnabled(!saver.isSaving());
                        int existing = timetable == null ? 0 : timetable.getCourseCount();
                        if (existing == 0) {
                            replaceConfirmed = true;
                            save();
                        } else {
                            showReplaceDialog(getString(R.string.timetable_replace_message, existing));
                        }
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        checkingExisting = null;
                        if (isFinishing() || isDestroyed()) return;
                        complete.setEnabled(!saver.isSaving());
                        showReplaceDialog(getString(R.string.timetable_replace_message_unknown));
                    }
                });
    }

    private void showReplaceDialog(String message) {
        if (getSupportFragmentManager().findFragmentByTag(CONFIRM_REPLACE) != null) return;
        ConfirmDialog.show(getSupportFragmentManager(), CONFIRM_REPLACE,
                getString(R.string.timetable_replace_title), message,
                getString(R.string.timetable_replace_confirm));
    }

    @Override
    protected void onDestroy() {
        if (checkingExisting != null) checkingExisting.remove();
        super.onDestroy();
    }

    private void showSaving(boolean saving) {
        complete.setEnabled(!saving);
        complete.setAlpha(saving ? 0.5f : 1f);
        blockBackWhileSaving.setEnabled(saving);
    }

    static String getSlotTimeRange(int slot) {
        return TimetableWeekView.slotRange(TimetableWeekView.FIRST_DEFAULT_SLOT + slot);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        draft.save(out);
        out.putBoolean(REPLACE_CONFIRMED, replaceConfirmed);
    }
}
