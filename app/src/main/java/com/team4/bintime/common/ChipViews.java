package com.team4.bintime.common;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.team4.bintime.R;

/** Figma 빈틈 매칭/Chip 을 코드로 생성하는 공통 헬퍼. 선택 상태는 View.setSelected 로 표현. */
public final class ChipViews {

    private ChipViews() {
    }

    /** Type=Filter: 선택 시 빨간 배경 + 흰 글자 */
    public static TextView filter(Context c, String label, boolean selected) {
        return create(c, label, selected, R.drawable.bg_chip_filter, R.color.chip_filter_text);
    }

    /** Type=Interest: 선택 시 연한 빨강 배경 + 진한 빨강 글자 */
    public static TextView interest(Context c, String label, boolean selected) {
        return create(c, label, selected, R.drawable.bg_chip_interest, R.color.chip_interest_text);
    }

    private static TextView create(Context c, String label, boolean selected, int bg, int textColor) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackgroundResource(bg);
        t.setTextColor(ContextCompat.getColorStateList(c, textColor));
        int h = dp(c, 12), v = dp(c, 8);
        t.setPadding(h, v, h, v);
        t.setSelected(selected);
        return t;
    }

    public static int dp(Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
