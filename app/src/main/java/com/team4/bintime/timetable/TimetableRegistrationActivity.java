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
import com.team4.bintime.data.repository.FreeSlotValidator;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.Collections;

/**
 * 공강 선택 후 "다음"(등록 완료). [BT-015/016] 공강 선택이 확정되는 지점이므로 여기서 저장한다:
 * 수업 전체 교체(replaceCourses) → 공강 선택 저장(saveFreeSlots, availability 갱신).
 * 저장이 끝나야 RESULT_OK로 돌아가며, 실패하면 안내하고 이 화면에 남는다.
 */
public class TimetableRegistrationActivity extends AppCompatActivity {
    private TimetableDraft draft;
    private TimetableSaveViewModel saver;
    private View complete;
    private OnBackPressedCallback blockBackWhileSaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_timetable_registration);
        draft = TimetableDraft.restore(state, getIntent());
        if (draft == null) draft = new TimetableDraft(Collections.emptyList());
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
    }

    private void save() {
        if (saver.isSaving()) return;
        draft.discardOccupiedFreeSlots();
        // 매칭 대상 공강 구간 상한(40개)은 저장 전에 안내하고 거부한다.
        if (FreeSlotValidator.checkLimit(draft.freeCells()) != null) {
            Toast.makeText(this, TimetableMessages.TOO_MANY_FREE_SLOTS, Toast.LENGTH_LONG).show();
            return;
        }
        showSaving(true);
        saver.save(TimetableRepositories.get(this),
                TimetableDraftMapper.toCourses(draft), TimetableDraftMapper.toFreeSlots(draft));
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
    }
}
