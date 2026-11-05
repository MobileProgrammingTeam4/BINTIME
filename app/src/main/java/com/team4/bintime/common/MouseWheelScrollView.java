package com.team4.bintime.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/** 에뮬레이터/마우스의 큰 휠 축 값이 한 번에 전체 목록을 넘기지 않도록 제한한다. */
public class MouseWheelScrollView extends NestedScrollView {
    public MouseWheelScrollView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            float vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (vertical != 0 && Float.isFinite(vertical)) {
                // 일반 휠/고해상도 휠의 방향과 작은 입력은 유지하고 이벤트당 최대 48dp만 이동.
                float steps = Math.max(-1f, Math.min(1f, vertical));
                scrollBy(0, -Math.round(steps * ChipViews.dp(getContext(), 48)));
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }
}
