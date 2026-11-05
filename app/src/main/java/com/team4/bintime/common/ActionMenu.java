package com.team4.bintime.common;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.team4.bintime.R;

/** Figma Buttons 팝업: 정렬 선택지·프로필 더보기의 공통 표시. */
public final class ActionMenu {
    private ActionMenu() { }

    public static PopupWindow show(View anchor, String[] labels, int highlighted,
                                   java.util.function.IntConsumer onSelected) {
        LinearLayout content = new LinearLayout(anchor.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ChipViews.dp(anchor.getContext(), 12), ChipViews.dp(anchor.getContext(), 8),
                ChipViews.dp(anchor.getContext(), 12), ChipViews.dp(anchor.getContext(), 8));
        PopupWindow popup = new PopupWindow(content, ChipViews.dp(anchor.getContext(), 116),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(ContextCompat.getDrawable(anchor.getContext(), R.drawable.bg_dialog));
        popup.setElevation(ChipViews.dp(anchor.getContext(), 8));
        popup.setOutsideTouchable(true);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView row = new TextView(anchor.getContext());
            row.setText(labels[i]);
            row.setTextSize(14);
            row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            row.setGravity(Gravity.CENTER);
            row.setTextColor(ContextCompat.getColor(anchor.getContext(), i == highlighted
                    ? R.color.action_pressed : R.color.text_secondary));
            int padding = ChipViews.dp(anchor.getContext(), 8);
            row.setPadding(padding, padding, padding, padding);
            row.setFocusable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            anchor.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> {
                popup.dismiss();
                onSelected.accept(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = ChipViews.dp(anchor.getContext(), 4);
            content.addView(row, params);
        }
        popup.showAsDropDown(anchor, 0, ChipViews.dp(anchor.getContext(), 4), Gravity.END);
        return popup;
    }
}
