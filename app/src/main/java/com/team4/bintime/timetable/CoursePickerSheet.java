package com.team4.bintime.timetable;

import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.team4.bintime.R;

public class CoursePickerSheet extends BottomSheetDialogFragment {
    static final String VALUE = "value";
    private static final String[] PERIODS = {"오전", "오후"};
    private static final String[] HOURS = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
    private static final String[] MINUTES = {"00", "30"};

    static CoursePickerSheet create(String key, String title, String value, boolean weekday) {
        CoursePickerSheet sheet = new CoursePickerSheet();
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("title", title);
        args.putString(VALUE, value);
        args.putBoolean("weekday", weekday);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        Bundle args = requireArguments();
        boolean weekday = args.getBoolean("weekday");
        View view = inflater.inflate(weekday ? R.layout.dialog_day_picker : R.layout.dialog_bottom_time_picker,
                container, false);
        ((TextView) view.findViewById(R.id.picker_title)).setText(args.getString("title"));
        String selected = args.getString(VALUE, "");
        if (weekday) {
            bindWeekdays(inflater, view, selected);
        } else {
            bindTime(view, selected, state);
        }
        return view;
    }

    private void bindWeekdays(LayoutInflater inflater, View view, String selected) {
        LinearLayout days = view.findViewById(R.id.picker_days);
        for (String day : TimetableDraft.DAYS) {
            String fullName = day + "요일";
            View row = inflater.inflate(R.layout.item_course_picker_day, days, false);
            ((TextView) row.findViewById(R.id.picker_day_name)).setText(fullName);
            boolean checked = fullName.equals(selected);
            row.setSelected(checked);
            ImageView check = row.findViewById(R.id.picker_day_check);
            check.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                    checked ? R.color.action_primary : R.color.timetable_disabled_text)));
            row.setOnClickListener(v -> select(fullName));
            days.addView(row);
        }
    }

    private void bindTime(View view, String selected, Bundle state) {
        int value = Course.parseMinute(selected);
        if (state != null) value = state.getInt("wheel_minutes", value);
        int hour24 = value / 60;
        int hour12 = (hour24 + 11) % 12 + 1;
        NumberPicker period = view.findViewById(R.id.npAmPm);
        NumberPicker hour = view.findViewById(R.id.npHour);
        NumberPicker minute = view.findViewById(R.id.npMinute);
        configure(period, PERIODS, hour24 / 12);
        configure(hour, HOURS, hour12 - 1);
        configure(minute, MINUTES, value % 60 / 30);
        view.findViewById(R.id.btnCancelTime).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btnConfirmTime).setOnClickListener(v ->
                select(Course.format24Hour(period.getValue() == 1, hour.getValue() + 1, minute.getValue() * 30)));
    }

    private void configure(NumberPicker picker, String[] values, int selection) {
        picker.setMinValue(0);
        picker.setMaxValue(values.length - 1);
        picker.setDisplayedValues(values);
        picker.setValue(selection);
        picker.setWrapSelectorWheel(values.length > 2);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.setSelectionDividerHeight(0);
            picker.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        }
    }

    private void select(String value) {
        Bundle result = new Bundle();
        result.putString(VALUE, value);
        getParentFragmentManager().setFragmentResult(requireArguments().getString("key", ""), result);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog dialog = (BottomSheetDialog) requireDialog();
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        View view = getView();
        if (view == null || requireArguments().getBoolean("weekday")) return;
        NumberPicker period = view.findViewById(R.id.npAmPm);
        NumberPicker hour = view.findViewById(R.id.npHour);
        NumberPicker minute = view.findViewById(R.id.npMinute);
        out.putInt("wheel_minutes", ((hour.getValue() + 1) % 12 + period.getValue() * 12) * 60
                + minute.getValue() * 30);
    }
}
