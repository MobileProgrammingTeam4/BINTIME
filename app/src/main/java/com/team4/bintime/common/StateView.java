package com.team4.bintime.common;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.team4.bintime.R;

/**
 * 공통 로딩 · 빈 상태 · 오류 · 완료 표시 (Figma 빈틈 매칭/Feedback).
 * 사용법: XML에 &lt;com.team4.bintime.common.StateView&gt; 를 두고
 * {@code stateView.showEmpty("제목", "설명").primary("버튼", v -> ...)} 처럼 호출. 목록이 있으면 {@link #hide()}.
 * 규칙: 데이터 요청 시작 → showLoading, 결과 0건 → showEmpty, 실패 → showError(재시도 버튼 필수), 성공 → hide.
 */
public class StateView extends LinearLayout {

    private final View progress, iconBg, buttons;
    private final ImageView icon;
    private final TextView title, message, primary, secondary;

    public StateView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int h = ChipViews.dp(context, 20), v = ChipViews.dp(context, 24);
        setPadding(h, v, h, v);
        inflate(context, R.layout.view_state, this);
        progress = findViewById(R.id.stateProgress);
        iconBg = findViewById(R.id.stateIconBg);
        icon = findViewById(R.id.stateIcon);
        title = findViewById(R.id.stateTitle);
        message = findViewById(R.id.stateMessage);
        buttons = findViewById(R.id.stateButtons);
        secondary = findViewById(R.id.stateSecondary);
        primary = findViewById(R.id.statePrimary);
        setVisibility(GONE);
    }

    public void showLoading() {
        setVisibility(VISIBLE);
        progress.setVisibility(VISIBLE);
        for (View view : new View[]{iconBg, title, message, buttons}) view.setVisibility(GONE);
    }

    public StateView showEmpty(String titleText, String messageText) {
        return show(R.drawable.ic_users_round, R.drawable.bg_circle_page, titleText, messageText);
    }

    /** 오류 화면. 호출하는 쪽에서 .primary("다시 시도", ...) 로 재시도 버튼을 붙인다. */
    public StateView showError(String messageText) {
        return show(R.drawable.ic_alert_circle, R.drawable.bg_circle_page, "문제가 생겼어요", messageText);
    }

    public StateView showSuccess(String titleText, String messageText) {
        return show(R.drawable.ic_check, R.drawable.bg_circle_subtle, titleText, messageText);
    }

    public StateView primary(String label, View.OnClickListener onClick) {
        buttons.setVisibility(VISIBLE);
        primary.setText(label);
        primary.setOnClickListener(onClick);
        return this;
    }

    public StateView secondary(String label, View.OnClickListener onClick) {
        buttons.setVisibility(VISIBLE);
        secondary.setVisibility(VISIBLE);
        secondary.setText(label);
        secondary.setOnClickListener(onClick);
        return this;
    }

    public void hide() {
        setVisibility(GONE);
    }

    private StateView show(@DrawableRes int iconRes, @DrawableRes int iconBgRes, String titleText, String messageText) {
        setVisibility(VISIBLE);
        progress.setVisibility(GONE);
        iconBg.setVisibility(VISIBLE);
        iconBg.setBackgroundResource(iconBgRes);
        icon.setImageResource(iconRes);
        title.setVisibility(VISIBLE);
        title.setText(titleText);
        message.setVisibility(VISIBLE);
        message.setText(messageText);
        // 버튼은 primary()/secondary() 를 부른 것만 보인다
        buttons.setVisibility(GONE);
        secondary.setVisibility(GONE);
        return this;
    }
}
