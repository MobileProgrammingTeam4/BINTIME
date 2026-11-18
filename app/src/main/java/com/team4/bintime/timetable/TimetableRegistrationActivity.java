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
import com.team4.bintime.profile.NetworkStatus;

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
 * <p>
 * [BT-026 후속, B 제안 ②] 저장 시작("다음"·확인 창의 교체·[다시 확인]) 전에 인터넷 연결을 본다(#101
 * {@link NetworkStatus#isOnline}). 오프라인이면 서버 읽기·확인 창·저장을 시작하지 않고 안내만 한다. 그래서 읽기 실패
 * 창은 온라인인데 서버 읽기가 실패한 경우에만 뜬다. 판단은 {@link TimetableReplaceCheck#begin}이 하고, 연결 확인은
 * {@link #network}로 주입한다.
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
    /** 인터넷 연결 확인. 기본은 #101 NetworkStatus. */
    TimetableReplaceCheck.Network network = () -> NetworkStatus.isOnline(this);
    /** 연타·회전해도 오프라인 안내가 쌓이지 않도록 직전 안내를 바꿔 끼운다(앱 Context 로 만들어 화면을 붙잡지 않음). */
    private static Toast offlineToast;

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
        complete.setOnClickListener(v -> save(false));
        // 확인 창의 교체도 저장 시작이라 연결을 다시 본다. 오프라인이면 교체 확인을 기억하지 않는다.
        getSupportFragmentManager().setFragmentResultListener(CONFIRM_REPLACE, this,
                (key, result) -> save(true));
        getSupportFragmentManager().setFragmentResultListener(ReplaceCheckFailedDialog.KEY_CONFIRM, this,
                (key, result) -> save(true));
        getSupportFragmentManager().setFragmentResultListener(ReplaceCheckFailedDialog.KEY_RETRY, this,
                (key, result) -> startSave(false));
    }

    /** @param confirmedNow 방금 확인 창에서 교체를 눌렀다. */
    private void save(boolean confirmedNow) {
        if (saver.isSaving()) return;
        draft.discardOccupiedFreeSlots();
        // 매칭 대상 공강 구간 상한(40개)은 저장 전에 안내하고 거부한다.
        if (FreeSlotValidator.checkLimit(draft.freeCells()) != null) {
            Toast.makeText(this, TimetableMessages.TOO_MANY_FREE_SLOTS, Toast.LENGTH_LONG).show();
            return;
        }
        startSave(confirmedNow);
    }

    /**
     * 저장 시작 관문({@link TimetableReplaceCheck#begin}). 오프라인이면 안내만 한다. 온라인이면 교체 확인이 필요할 때
     * 기존 수업 수를 서버에서 읽고, 있으면 교체 확인을, 없으면 바로 저장한다. 읽지 못하면 [다시 확인]이 있는 실패
     * 확인을 띄운다. "다음"과 [다시 확인] 모두 여기로 온다.
     */
    private void startSave(boolean confirmedNow) {
        if (checkingExisting || saver.isSaving()) return;
        checkingExisting = true;
        complete.setEnabled(false);
        boolean needsCheck = draft.replacesExisting() && !replaceConfirmed && !confirmedNow;
        TimetableReplaceCheck.begin(network, needsCheck, TimetableRepositories.get(this),
                new TimetableReplaceCheck.Start() {
            @Override
            public void offline() {
                if (!finishCheck()) return;
                showOffline();
            }

            @Override
            public void proceed() {
                if (!finishCheck()) return;
                if (confirmedNow) replaceConfirmed = true;
                startSaving();
            }

            @Override
            public void saveWithoutConfirm() {
                if (!finishCheck()) return;
                replaceConfirmed = true;
                startSaving();
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

    private void startSaving() {
        showSaving(true);
        saver.save(TimetableRepositories.get(this),
                TimetableDraftMapper.toCourses(draft), TimetableDraftMapper.toFreeSlots(draft));
    }

    private void showOffline() {
        if (offlineToast != null) offlineToast.cancel();
        offlineToast = Toast.makeText(getApplicationContext(), R.string.timetable_save_offline, Toast.LENGTH_SHORT);
        offlineToast.show();
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
