package com.team4.bintime.timetable;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

public class TimetableWeekFragment extends Fragment {
    private final ActivityResultLauncher<Intent> manage = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> render());

    public TimetableWeekFragment() {
        super(R.layout.fragment_timetable_week);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle state) {
        view.findViewById(R.id.manage_timetable).setOnClickListener(v ->
                manage.launch(new Intent(requireContext(), TimetableSelectModeActivity.class)));
        view.findViewById(R.id.btnChangeSlot).setVisibility(View.GONE);
        TextView footer = view.findViewById(R.id.matching_status_footer);
        footer.setGravity(Gravity.CENTER);
        footer.setText("시간표의 빈공간을 눌러 공강시간을 선택할 수 있어요");
        render();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) render();
    }

    private void render() {
        View view = getView();
        if (view == null) return;
        MainActivity host = (MainActivity) requireActivity();
        TimetableDraft saved = MockTimetableRepository.load();
        TimetableDraft draft = saved == null ? new TimetableDraft(Collections.emptyList()) : saved;
        TimetableWeekView week = view.findViewById(R.id.timetable_week);
        week.setTitle("주간 시간표");
        week.bind(draft, () -> {
            MockTimetableRepository.save(draft);
            updateStatus(view, host, draft);
        });
        MaterialSwitch toggle = view.findViewById(R.id.switchMatching);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(host.isMatchingOn());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            host.setMatchingOn(checked);
            updateStatus(view, host, draft);
        });
        updateStatus(view, host, draft);
    }

    private void updateStatus(View view, MainActivity host, TimetableDraft draft) {
        boolean enabled = host.isMatchingOn();
        view.findViewById(R.id.statusCard).setBackgroundResource(
                enabled ? R.drawable.bg_status_on : R.drawable.bg_status_off);
        TextView title = view.findViewById(R.id.statusTitle);
        TextView description = view.findViewById(R.id.statusDesc);
        if (!enabled) {
            title.setText("매칭이 꺼져있어요");
            description.setText("공강시간에 함께할 공강메이트를 찾아보세요");
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        TimetableDraft.FreeWindow next = draft.nextFreeWindow(now);
        if (next == null) {
            title.setText("매칭 할 공강이 없어요");
            description.setText("시간표에서 공강시간을 선택해주세요");
            return;
        }
        String date = next.date.equals(now.toLocalDate()) ? "오늘" : next.date.format(DateTimeFormatter.ofPattern("M/d"));
        String day = TimetableDraft.DAYS[next.date.getDayOfWeek().getValue() - 1];
        title.setText(date + "(" + day + ") " + TimetableWeekView.slotRange(next.start, next.end) + " 공강 매칭중");
        description.setText("가장 가까운 공강으로 매칭이 진행돼요");
    }
}
