package com.team4.bintime.common;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.team4.bintime.R;

/** 기존 view_time_overlap의 Figma Type=Compact(27:1875) 표현. 상세 화면 기본 모양은 바꾸지 않는다. */
public final class TimeMatchViews {
    private TimeMatchViews() {}
    public static void bindCompact(View timeMatch, String time, String duration) {
        timeMatch.findViewById(R.id.timeOverlapDivider).setVisibility(View.GONE);
        timeMatch.findViewById(R.id.reasons).setVisibility(View.GONE);
        timeMatch.findViewById(R.id.aiPlace).setVisibility(View.GONE);
        TextView timeView = timeMatch.findViewById(R.id.time);
        TextView durationView = timeMatch.findViewById(R.id.duration);
        timeView.setText(time); durationView.setText(duration);
        LinearLayout.LayoutParams timeParams = (LinearLayout.LayoutParams) timeView.getLayoutParams();
        timeParams.width = 0; timeParams.weight = 1; timeParams.setMarginStart(ChipViews.dp(timeMatch.getContext(), 12));
        timeView.setLayoutParams(timeParams);
        LinearLayout.LayoutParams durationParams = (LinearLayout.LayoutParams) durationView.getLayoutParams();
        durationParams.width = 0; durationParams.weight = 1;
        durationView.setLayoutParams(durationParams); durationView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    }
}
