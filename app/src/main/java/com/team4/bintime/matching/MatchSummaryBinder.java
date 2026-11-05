package com.team4.bintime.matching;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.FeedbackDialog;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

/** 매칭 카드와 프로필 상세가 같이 쓰는 화면 채우기·매칭 신청 처리. */
final class MatchSummaryBinder {

    private MatchSummaryBinder() {
    }

    /** view_match_summary.xml 채우기. showPlace=true 면 AI 추천 장소 줄도 표시(상세 화면). */
    static void bind(View summary, MatchCandidate c, boolean showPlace) {
        // shortcut: 모든 후보에 기본 프로필 이미지, Firebase Storage 프로필 사진 연동 시 사진이 있으면 교체
        ((ImageView) summary.findViewById(R.id.avatar)).setImageResource(R.drawable.img_default_profile);
        text(summary, R.id.name, c.name);
        text(summary, R.id.info, c.department + " " + c.grade + "학년 · " + c.gender);
        text(summary, R.id.matchRate, "매칭률 " + c.matchRate + "%");
        text(summary, R.id.matchReason, c.matchReason);
        text(summary, R.id.time, "오늘 " + c.overlapStart + " ~ " + c.overlapEnd);
        text(summary, R.id.duration, MatchFilter.formatDuration(c.overlapMinutes()) + " 겹침");
        text(summary, R.id.reasons, String.join("\n", c.reasons));
        TextView place = summary.findViewById(R.id.aiPlace);
        boolean hasPlace = showPlace && c.aiPlace != null;
        place.setVisibility(hasPlace ? View.VISIBLE : View.GONE);
        if (hasPlace) place.setText("AI 추천 장소: " + c.aiPlace);
    }

    /** [매칭 신청] 버튼: 이미 신청했으면 '신청 완료'로 잠그고, 아니면 누를 때 신청 + 완료 팝업. */
    static void bindRequestButton(TextView button, MatchCandidate c) {
        boolean requested = MockMatchRepository.isRequested(c.uid);
        button.setText(requested ? "신청 완료" : "매칭 신청");
        button.setEnabled(!requested);
        button.setAlpha(requested ? 0.4f : 1f);
        button.setOnClickListener(v -> {
            if (!MockMatchRepository.requestMatch(c.uid)) return; // 연타·중복 신청 방지
            bindRequestButton(button, c);
            FeedbackDialog.success(activityOf(v.getContext()).getSupportFragmentManager(),
                    "매칭 신청이 완료됐어요!", c.name + "님이 수락하면\n채팅을 시작할 수 있어요.");
        });
    }

    /** View 의 context 에서 팝업을 띄울 화면(Activity)을 찾는다. */
    private static FragmentActivity activityOf(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof FragmentActivity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        return (FragmentActivity) context;
    }

    private static void text(View root, int id, String value) {
        ((TextView) root.findViewById(id)).setText(value);
    }
}
