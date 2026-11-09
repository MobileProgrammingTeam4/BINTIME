package com.team4.bintime.timetable;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;

import java.util.Collections;

public class TimetableRegistrationActivity extends AppCompatActivity {
    private TimetableDraft draft;

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
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCompleteRegistration).setOnClickListener(v -> {
            setResult(RESULT_OK, draft.result());
            finish();
        });
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
