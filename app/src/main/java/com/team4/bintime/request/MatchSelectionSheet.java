package com.team4.bintime.request;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.team4.bintime.R;
import com.team4.bintime.common.TimeMatchViews;
import com.team4.bintime.model.MatchFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 선택 결과만 전달한다. 실제 종료/후기 권한은 호출 화면과 서버에서 다시 검증한다. */
public class MatchSelectionSheet extends BottomSheetDialogFragment {
    public static final String MATCH_ID = "matchId", ACTION = "action";
    private static final String ITEMS = "items", KEY = "resultKey", NOW = "now";

    /** 대상 없음=false, 한 건=즉시 결과, 여러 건=선택 창. 상태 저장 뒤에는 열지 않는다. */
    public static boolean show(FragmentManager fm, String resultKey, MatchAppointmentPolicy.Action action,
                               List<MatchAppointment> all, long now) {
        if (fm.isStateSaved()) return false;
        List<MatchAppointment> targets = MatchAppointmentPolicy.targets(all, action);
        if (targets.isEmpty()) return false;
        if (targets.size() == 1) { fm.setFragmentResult(resultKey, result(targets.get(0).matchId, action)); return true; }
        if (fm.findFragmentByTag(resultKey) != null) return true;
        Bundle args = new Bundle(); args.putString(KEY, resultKey); args.putString(ACTION, action.name());
        args.putSerializable(ITEMS, new ArrayList<>(targets)); args.putLong(NOW, now);
        MatchSelectionSheet sheet = new MatchSelectionSheet(); sheet.setArguments(args); sheet.show(fm, resultKey);
        return true;
    }
    private static Bundle result(String id, MatchAppointmentPolicy.Action action) {
        Bundle result = new Bundle(); result.putString(MATCH_ID, id); result.putString(ACTION, action.name()); return result;
    }
    @NonNull @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        View view = inflater.inflate(R.layout.sheet_match_selection, parent, false);
        MatchAppointmentPolicy.Action action = MatchAppointmentPolicy.Action.valueOf(requireArguments().getString(ACTION));
        ((TextView) view.findViewById(R.id.matchSelectionTitle)).setText(action == MatchAppointmentPolicy.Action.END ? R.string.appointment_end_title : R.string.appointment_review_title);
        ((TextView) view.findViewById(R.id.matchSelectionHint)).setText(action == MatchAppointmentPolicy.Action.END ? R.string.appointment_end_hint : R.string.appointment_review_hint);
        @SuppressWarnings("unchecked") ArrayList<MatchAppointment> items = (ArrayList<MatchAppointment>) requireArguments().getSerializable(ITEMS);
        LinearLayout rows = view.findViewById(R.id.matchSelectionRows);
        if (items != null) for (MatchAppointment item : items) {
            View row = inflater.inflate(R.layout.item_match_selection, rows, false);
            ((TextView) row.findViewById(R.id.appointmentDate)).setText(item.meetingDate.replace('-', '.'));
            TimeMatchViews.bindCompact(row.findViewById(R.id.appointmentTimeMatch), String.format(Locale.KOREA, "%02d:%02d ~ %02d:%02d",
                    item.startMinute / 60, item.startMinute % 60, item.endMinute / 60, item.endMinute % 60),
                    getString(R.string.chat_overlap_duration, MatchFilter.formatDuration(item.endMinute - item.startMinute)));
            boolean ongoing = item.startAtMillis() <= requireArguments().getLong(NOW) && item.endAtMillis() > requireArguments().getLong(NOW);
            ((TextView) row.findViewById(R.id.appointmentStatus)).setText(action == MatchAppointmentPolicy.Action.REVIEW
                    ? item.reviewWritten ? R.string.appointment_review_written : R.string.appointment_review_available
                    : ongoing ? R.string.appointment_ongoing : item.endAtMillis() <= requireArguments().getLong(NOW) ? R.string.appointment_elapsed : R.string.appointment_upcoming);
            row.setOnClickListener(v -> {
                getParentFragmentManager().setFragmentResult(requireArguments().getString(KEY), result(item.matchId, action)); dismiss();
            });
            rows.addView(row);
        }
        view.findViewById(R.id.matchSelectionClose).setOnClickListener(v -> dismiss());
        View scroll = view.findViewById(R.id.matchSelectionScroll);
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = Math.min(com.team4.bintime.common.ChipViews.dp(requireContext(), Math.max(1, rows.getChildCount()) * 96),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45f)); scroll.setLayoutParams(params);
        return view;
    }
    public static String timeLabel(MatchAppointment item) {
        return String.format(Locale.KOREA, "%s · %02d:%02d ~ %02d:%02d", item.meetingDate.replace('-', '.'),
                item.startMinute / 60, item.startMinute % 60, item.endMinute / 60, item.endMinute % 60);
    }
    @Override public void onStart() {
        super.onStart();
        BottomSheetDialog dialog = (BottomSheetDialog) requireDialog();
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
