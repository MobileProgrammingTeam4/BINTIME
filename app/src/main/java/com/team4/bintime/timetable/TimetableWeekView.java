package com.team4.bintime.timetable;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.team4.bintime.R;
import com.team4.bintime.common.ChipViews;

import java.util.Locale;

public class TimetableWeekView extends LinearLayout {
    static final int FIRST_DEFAULT_SLOT = 18;
    private static final int END_DEFAULT_SLOT = 36;
    private static final int MINUTES = TimetableDraft.SLOT_MINUTES;
    private final LinearLayout table;

    public TimetableWeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_timetable_week, this, true);
        table = findViewById(R.id.week_table);
    }

    void setTitle(String title) {
        ((TextView) findViewById(R.id.week_title)).setText(title);
    }

    void bind(TimetableDraft draft, Runnable onChange) {
        table.removeAllViews();
        int first = FIRST_DEFAULT_SLOT;
        int end = END_DEFAULT_SLOT;
        for (Course course : draft.courses) {
            first = Math.min(first, course.getStartSlotIndex(0) / 2 * 2);
            end = Math.max(end, (course.getEndSlotIndex(0) + 1) / 2 * 2);
        }
        int rowHeight = ChipViews.dp(getContext(), 32);
        addTimeAxis(first, end, rowHeight);
        for (int day = 0; day < TimetableDraft.DAYS.length; day++) {
            addDay(draft, day, first, end, rowHeight, onChange);
        }
    }

    private void addTimeAxis(int first, int end, int rowHeight) {
        LinearLayout axis = new LinearLayout(getContext());
        axis.setOrientation(VERTICAL);
        axis.addView(header(""), new LayoutParams(-1, ChipViews.dp(getContext(), 36)));
        for (int slot = first; slot < end; slot++) {
            TextView hour = label(slot % 2 == 0 ? String.valueOf(slot / 2) : "", 11, R.color.text_tertiary);
            hour.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            hour.setPadding(0, ChipViews.dp(getContext(), 4), 0, 0);
            hour.setBackgroundResource(slot % 2 == 0 ? R.drawable.bg_timetable_axis_hour : R.drawable.cell_empty);
            axis.addView(hour, new LayoutParams(-1, rowHeight));
        }
        table.addView(axis, new LayoutParams(ChipViews.dp(getContext(), 28), -2));
    }

    private void addDay(TimetableDraft draft, int day, int first, int end, int rowHeight, Runnable onChange) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.addView(header(TimetableDraft.DAYS[day]), new LayoutParams(-1, ChipViews.dp(getContext(), 36)));
        FrameLayout body = new FrameLayout(getContext());
        TextView[] slots = new TextView[end - first];
        for (int slot = first; slot < end; slot++) {
            int position = slot;
            TextView cell = label("", 11, R.color.action_primary);
            cell.setTypeface(null, Typeface.BOLD);
            cell.setPadding(ChipViews.dp(getContext(), 3), ChipViews.dp(getContext(), 3), 0, 0);
            slots[slot - first] = cell;
            paintSlot(cell, draft, day, slot);
            if (draft.courseAt(day, slot) == null) {
                cell.setFocusable(true);
                cell.setOnClickListener(v -> {
                    draft.toggleFree(day, position);
                    int start = Math.max(first, position - 1);
                    int finish = Math.min(end - 1, position + 1);
                    for (int refresh = start; refresh <= finish; refresh++) {
                        paintSlot(slots[refresh - first], draft, day, refresh);
                    }
                    onChange.run();
                });
            } else {
                cell.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            addAt(body, cell, (slot - first) * rowHeight, rowHeight);
        }

        for (Course course : draft.courses) {
            if (course.getDayIndex() != day) continue;
            int top = Math.round((course.getStartMinute() - first * MINUTES) * rowHeight / (float) MINUTES);
            int height = Math.round((course.getEndMinute() - course.getStartMinute()) * rowHeight / (float) MINUTES);
            LinearLayout block = new LinearLayout(getContext());
            block.setOrientation(VERTICAL);
            block.setPadding(ChipViews.dp(getContext(), 3), ChipViews.dp(getContext(), 3),
                    ChipViews.dp(getContext(), 3), 0);
            block.setBackgroundResource(R.drawable.cell_class_single);
            block.setContentDescription(course.getName() + ", " + course.getFormattedInfo());
            block.setFocusable(true);
            TextView name = label(course.getName(), 10, R.color.text_primary);
            name.setTypeface(null, Typeface.BOLD);
            name.setMaxLines(height >= rowHeight * 2 ? 2 : 1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView room = label(course.getClassroom(), 9, R.color.text_secondary);
            room.setMaxLines(1);
            room.setEllipsize(TextUtils.TruncateAt.END);
            block.addView(name);
            block.addView(room);
            addAt(body, block, top, height);
        }
        column.addView(body, new LayoutParams(-1, (end - first) * rowHeight));
        table.addView(column, new LayoutParams(0, -2, 1f));
    }

    private void paintSlot(TextView cell, TimetableDraft draft, int day, int slot) {
        boolean selected = draft.isFreeSelected(day, slot);
        boolean above = slot > 0 && draft.isFreeSelected(day, slot - 1);
        boolean below = slot < 47 && draft.isFreeSelected(day, slot + 1);
        int background = R.drawable.cell_empty;
        if (selected) background = below ? R.drawable.cell_free_top : R.drawable.cell_free_single;
        cell.setBackgroundResource(background);
        cell.setText(selected && !above ? "공강" : "");
        cell.setSelected(selected);
        cell.setContentDescription(TimetableDraft.DAYS[day] + "요일 " + slotRange(slot)
                + (selected ? " 공강 선택됨" : " 공강 선택"));
    }

    private TextView header(String text) {
        TextView view = label(text, 13, R.color.text_primary);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_timetable_day_header);
        return view;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(ContextCompat.getColor(getContext(), color));
        view.setIncludeFontPadding(false);
        return view;
    }

    private void addAt(FrameLayout container, android.view.View child, int top, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, height);
        params.topMargin = top;
        container.addView(child, params);
    }

    static String slotRange(int slot) {
        return slotRange(slot, slot + 1);
    }

    static String slotRange(int start, int end) {
        return String.format(Locale.KOREA, "%02d:%02d ~ %02d:%02d",
                start / 2, start % 2 * MINUTES, end / 2, end % 2 * MINUTES);
    }
}
