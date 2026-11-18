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
import com.team4.bintime.data.repository.FreeSlotValidator;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.Collections;

/**
 * 공강 선택 후 "다음"(등록 완료). [BT-015/016] 공강 선택이 확정되는 지점이므로 여기서 저장한다:
 * 수업 전체 교체(replaceCourses) → 공강 선택 저장(saveFreeSlots, availability 갱신).
 * 저장이 끝나야 RESULT_OK로 돌아가며, 실패하면 안내하고 이 화면에 남는다.
 * <p>
 * [BT-026 결정 0-3] 새 시간표 등록(이미지 인식·직접 입력 새로 시작, {@link TimetableDraft#replacesExisting()})은
 * replaceCourses 가 기존 수업을 모두 바꾸므로, 기존 수업이 있으면 저장 전에 "기존 수업이 새 시간표로 교체됩니다"
 * 확인을 받는다. 기존 수업 수는 서버에서 읽은 값만 쓰고(캐시 0개를 믿지 않음), 읽지 못하면 수를 빼고 같은 확인을
 * 띄운다({@link TimetableReplaceCheck}).
 * <p>
 * 읽기 실패 확인({@link ReplaceCheckFailedDialog})은 별도 문구와 [다시 확인]을 갖는다. 다시 확인은 서버에서 다시
 * 읽어 성공하면 수를 보여 주는 확인(0개면 바로 저장)으로, 실패하면 같은 창을 다시 띄운다. 읽기 실패는 사용자가
 * [교체하기]를 누르지 않는 한 저장으로 이어지지 않는다.
 */
public class TimetableRegistrationActivity extends AppCompatActivity {
    private static final String CONFIRM_REPLACE = "timetable_confirm_replace";
    private static final String REPLACE_CONFIRMED = "timetable_replace_confirmed";
    private TimetableDraft draft;
    /** 교체 확인을 받았거나 기존 수업이 없음을 확인했다. */
    private boolean replaceConfirmed;
    /** 서버에서 기존 수업 수를 읽는 중(중복 클릭 방지). */
    private boolean checkingExisting;
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
        getSupportFragmentManager().setFragmentResultListener(ReplaceCheckFailedDialog.KEY_CONFIRM, this,
                (key, result) -> {
                    replaceConfirmed = true;
                    save();
                });
        getSupportFragmentManager().setFragmentResultListener(ReplaceCheckFailedDialog.KEY_RETRY, this,
                (key, result) -> confirmReplace());
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

    /**
     * 기존 수업 수를 서버에서 읽고, 있으면 교체 확인을 띄운다. 없으면 바로 저장한다. 읽지 못하면 [다시 확인]이 있는
     * 실패 확인을 띄운다. 처음 확인과 [다시 확인] 모두 여기로 온다.
     */
    private void confirmReplace() {
        if (checkingExisting) return;
        checkingExisting = true;
        complete.setEnabled(false);
        TimetableReplaceCheck.check(TimetableRepositories.get(this), new TimetableReplaceCheck.Screen() {
            @Override
            public void saveWithoutConfirm() {
                if (!finishCheck()) return;
                replaceConfirmed = true;
                save();
            }

            @Override
            public void confirmWithCount(int count) {
                if (!finishCheck()) return;
                showReplaceDialog(getString(
                        TimetableReplaceCheck.messageRes(TimetableReplaceCheck.Kind.CONFIRM_WITH_COUNT), count));
            }

            @Override
            public void confirmUnknown() {
                if (!finishCheck()) return;
                ReplaceCheckFailedDialog.show(getSupportFragmentManager());
            }
        });
    }

    /** 서버 읽기가 끝났다. 화면이 이미 닫혔으면 false(아무것도 하지 않는다). */
    private boolean finishCheck() {
        checkingExisting = false;
        if (isFinishing() || isDestroyed()) return false;
        complete.setEnabled(!saver.isSaving());
        return true;
    }

    private void showReplaceDialog(String message) {
        if (getSupportFragmentManager().findFragmentByTag(CONFIRM_REPLACE) != null) return;
        ConfirmDialog.show(getSupportFragmentManager(), CONFIRM_REPLACE,
                getString(TimetableReplaceCheck.titleRes(TimetableReplaceCheck.Kind.CONFIRM_WITH_COUNT)), message,
                getString(R.string.timetable_replace_confirm));
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
