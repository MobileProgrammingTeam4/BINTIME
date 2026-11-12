package com.team4.bintime.matching;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.FeedbackDialog;
import com.team4.bintime.request.RequestDraft;
import com.team4.bintime.request.RequestRepositories;
import com.team4.bintime.request.RequestRepository;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;

/** 매칭 카드와 프로필 상세가 같이 쓰는 화면 채우기·매칭 신청 처리. */
public final class MatchSummaryBinder {

    private MatchSummaryBinder() {
    }

    /** view_match_summary.xml 채우기. showPlace=true 면 AI 추천 장소 줄도 표시(상세 화면). */
    public static void bind(View summary, MatchCandidate c, boolean showPlace) {
        bindAvatar(summary.findViewById(R.id.avatar), c.photoPath);
        text(summary, R.id.name, c.name);
        text(summary, R.id.info, c.department + " " + c.grade + "학년 · " + c.gender);
        text(summary, R.id.matchRate, "매칭률 " + c.matchRate + "%");
        // AI 매칭률(BT-027/028) 연결 전 실데이터 후보는 0 이라 숨긴다
        summary.findViewById(R.id.matchRate).setVisibility(c.matchRate > 0 ? View.VISIBLE : View.GONE);
        text(summary, R.id.matchReason, c.matchReason);
        text(summary, R.id.time, c.dayLabel + " " + c.overlapStart + " ~ " + c.overlapEnd);
        text(summary, R.id.duration, MatchFilter.formatDuration(c.overlapMinutes()) + " 겹침");
        text(summary, R.id.reasons, String.join("\n", c.reasons));
        TextView place = summary.findViewById(R.id.aiPlace);
        boolean hasPlace = showPlace && c.aiPlace != null;
        place.setVisibility(hasPlace ? View.VISIBLE : View.GONE);
        if (hasPlace) place.setText("AI 추천 장소: " + c.aiPlace);
    }

    /** [매칭 신청] 버튼: 이미 신청했으면 '신청 완료'로 잠그고, 아니면 누를 때 신청 + 완료 팝업. */
    static void bindRequestButton(TextView button, MatchCandidate c) {
        RequestRepository repository = RequestRepositories.get(button.getContext());
        bindRequestButton(button, c, repository.isMock() ? RequestDraft.mock(c) : null);
    }

    /** BT-018 연결점. 실제 검색 후보는 verified draft를 공급한다. Mock 카드를 Firebase로 보내지 않는다. */
    public static void bindRequestButton(TextView button, MatchCandidate c, RequestDraft draft) {
        RequestRepository repository = RequestRepositories.get(button.getContext());
        boolean requested = repository.hasPendingRequest(c.uid);
        boolean submitting = repository.isSubmitting(c.uid);
        button.setText(submitting ? "신청 중…" : requested ? "신청 완료" : "매칭 신청");
        button.setEnabled(!requested && !submitting);
        button.setAlpha(requested || submitting ? 0.4f : 1f);
        button.setOnClickListener(v -> {
            FragmentActivity activity = activityOf(v.getContext());
            if (draft == null || draft.mock != repository.isMock() || !c.uid.equals(draft.toUid)
                    || (!repository.isMock() && !c.uid.equals(draft.candidateId))) {
                Toast.makeText(activity, "매칭 검색 결과에서 상대를 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            button.setEnabled(false);
            repository.create(draft, new RequestRepository.Callback() {
                @Override public void onSuccess(RequestRepository.Item item) {
                    if (activity.isFinishing() || activity.isDestroyed() || !button.isAttachedToWindow()) return;
                    bindRequestButton(button, c);
                    if (!activity.getSupportFragmentManager().isStateSaved()) FeedbackDialog.success(
                            activity.getSupportFragmentManager(), "매칭 신청이 완료됐어요!",
                            c.name + "님이 수락하면\n채팅을 시작할 수 있어요.");
                }
                @Override public void onError(String message) {
                    if (activity.isFinishing() || activity.isDestroyed() || !button.isAttachedToWindow()) return;
                    // 네트워크 실패 후 같은 draft/UUID로 재시도할 수 있도록 리스너를 바꾸지 않는다.
                    button.setEnabled(true);
                    button.setText("매칭 신청");
                    button.setAlpha(1f);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 기본 이미지를 먼저 깔고 Storage 사진이 오면 교체한다. 카드 재사용 시 다른 후보 사진이 늦게 덮지 않도록
     * 경로를 tag 로 비교한다. shortcut: 메모리 캐시 없음, 스크롤로 다시 붙을 때마다 다시 받는다.
     */
    private static void bindAvatar(ImageView avatar, String photoPath) {
        avatar.setTag(photoPath);
        avatar.setImageResource(R.drawable.img_default_profile);
        if (photoPath == null || photoPath.isEmpty()) return;
        ProfileRepositories.get(avatar.getContext()).loadProfilePhoto(photoPath, new ProfileRepository.Callback<Bitmap>() {
            @Override public void onSuccess(Bitmap bitmap) {
                if (photoPath.equals(avatar.getTag())) avatar.setImageBitmap(bitmap);
            }
            @Override public void onError(String message) {
                // 사진만 실패하면 기본 이미지를 유지한다.
            }
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
