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

import java.util.UUID;

/** 매칭 카드와 프로필 상세가 같이 쓰는 화면 채우기·매칭 신청 처리. */
public final class MatchSummaryBinder {

    private MatchSummaryBinder() {
    }

    /** view_match_summary.xml 채우기. showPlace=true 면 추천 장소 줄도 표시(상세 화면). */
    public static void bind(View summary, MatchCandidate c, boolean showPlace) {
        bindAvatar(summary.findViewById(R.id.avatar), c.photoPath);
        text(summary, R.id.name, c.name);
        text(summary, R.id.info, c.department + " " + c.grade + "학년 · " + c.gender);
        text(summary, R.id.matchRate, "매칭률 " + c.matchRate + "%");
        // BT-027: 점수 없음(-1)만 숨기고 0점은 0%로 보여준다
        summary.findViewById(R.id.matchRate).setVisibility(c.hasScore() ? View.VISIBLE : View.GONE);
        text(summary, R.id.matchReason, c.matchReason);
        text(summary, R.id.time, c.dayLabel + " " + c.overlapStart + " ~ " + c.overlapEnd);
        text(summary, R.id.duration, MatchFilter.formatDuration(c.overlapMinutes()) + " 겹침");
        text(summary, R.id.reasons, String.join("\n", c.reasons));
        TextView place = summary.findViewById(R.id.aiPlace);
        boolean hasPlace = showPlace && c.aiPlace != null;
        place.setVisibility(hasPlace ? View.VISIBLE : View.GONE);
        if (hasPlace) place.setText("추천 장소: " + c.aiPlace); // BT-042: 검색 단계는 AI 가 아닌 검증 목록 규칙이라 "AI" 를 붙이지 않는다
    }

    /** [매칭 신청] 버튼: 이미 신청했으면 '신청 완료'로 잠그고, 아니면 누를 때 신청 + 완료 팝업. */
    static void bindRequestButton(TextView button, MatchCandidate c) {
        RequestRepository repository = RequestRepositories.get(button.getContext());
        // BT-018: Firebase 모드 후보는 searchMatches 결과뿐이라 candidateId(= 상대 uid)로 보내고 서버가 재판정한다.
        // UUID 멱등은 같은 리스너 재시도까지만. 재바인딩은 새 UUID, 중복은 서버 PENDING 잠금이 막는다.
        bindRequestButton(button, c, requestDraft(repository, c), repository);
    }

    private static RequestDraft requestDraft(RequestRepository repository, MatchCandidate candidate) {
        return repository.isMock() ? RequestDraft.mock(candidate)
                : RequestDraft.verified(candidate.uid, candidate.uid, UUID.randomUUID().toString());
    }

    /** BT-018 연결점. 실제 검색 후보는 verified draft를 공급한다. Mock 카드를 Firebase로 보내지 않는다. */
    public static void bindRequestButton(TextView button, MatchCandidate c, RequestDraft draft) {
        RequestRepository repository = RequestRepositories.get(button.getContext());
        bindRequestButton(button, c, draft, repository);
    }

    /** 저장소를 주입해 지연 응답을 실제 View에서 검증하는 패키지 내부 진입점. */
    static void bindRequestButton(TextView button, MatchCandidate c, RequestDraft draft, RequestRepository repository) {
        RequestButtonBinding binding = new RequestButtonBinding(repository, c.uid);
        button.setTag(R.id.request_button_binding, binding);
        binding.render(button);
        button.setOnClickListener(v -> {
            FragmentActivity activity = activityOf(v.getContext());
            if (draft == null || draft.mock != repository.isMock() || !c.uid.equals(draft.toUid)
                    || (!repository.isMock() && !c.uid.equals(draft.candidateId))) {
                Toast.makeText(activity, "매칭 검색 결과에서 상대를 다시 선택해주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            button.setEnabled(false);
            button.setText(R.string.request_submitting);
            button.setAlpha(0.4f);
            repository.create(draft, new RequestRepository.Callback() {
                @Override public void onSuccess(RequestRepository.Item item) {
                    if (!canHandleResult(activity, button, binding)) return;
                    bindRequestButton(button, c, requestDraft(repository, c), repository);
                    switch (item.status()) {
                        case PENDING:
                            if (!activity.getSupportFragmentManager().isStateSaved()) FeedbackDialog.success(
                                    activity.getSupportFragmentManager(), activity.getString(R.string.request_create_success_title),
                                    activity.getString(R.string.request_create_success_message, c.name));
                            break;
                        case ACCEPTED:
                            Toast.makeText(activity, R.string.request_already_accepted, Toast.LENGTH_LONG).show();
                            break;
                        case REJECTED:
                            Toast.makeText(activity, R.string.request_already_rejected, Toast.LENGTH_LONG).show();
                            break;
                        case CANCELED:
                            Toast.makeText(activity, R.string.request_already_canceled, Toast.LENGTH_LONG).show();
                            break;
                    }
                }
                @Override public void onError(String message) {
                    if (!canHandleResult(activity, button, binding)) return;
                    // 네트워크 실패 후 같은 draft/UUID로 재시도할 수 있도록 리스너를 바꾸지 않는다.
                    binding.render(button);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 객체 하나가 바인딩 한 세대다. 같은 UID를 다시 붙인 경우도 이전 콜백과 구분한다. */
    private static final class RequestButtonBinding {
        final RequestRepository repository;
        final String candidateUid;

        RequestButtonBinding(RequestRepository repository, String candidateUid) {
            this.repository = repository;
            this.candidateUid = candidateUid;
        }

        void render(TextView button) {
            boolean requested = repository.hasPendingRequest(candidateUid);
            boolean submitting = repository.isSubmitting(candidateUid);
            button.setText(submitting ? R.string.request_submitting
                    : requested ? R.string.request_submitted : R.string.request_create);
            button.setEnabled(!requested && !submitting);
            button.setAlpha(requested || submitting ? 0.4f : 1f);
        }
    }

    private static boolean canHandleResult(FragmentActivity activity, TextView button, RequestButtonBinding binding) {
        if (activity.isFinishing() || activity.isDestroyed() || !button.isAttachedToWindow()) return false;
        Object current = button.getTag(R.id.request_button_binding);
        if (current == binding) return true;
        // 과거 후보의 메시지·클릭 리스너는 적용하지 않는다. 현재 후보의 저장소 상태만 갱신한다.
        // 동일 후보 재바인딩 중 완료된 경우에도 "신청 중…"에 머무르지 않게 한다.
        if (current instanceof RequestButtonBinding) ((RequestButtonBinding) current).render(button);
        return false;
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
