package com.team4.bintime.request;

import android.view.LayoutInflater;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.team4.bintime.R;
import com.team4.bintime.MainActivity;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.common.StateView;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.matching.ProfileDetailActivity;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.UserProfile;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 기존 요청 카드/확인창을 공통 저장소의 로딩·빈 상태·오류·실시간 상태로 채운다. */
public final class RequestTabsController {
    private static final String ACTION_RESULT = "requestAction";
    private final Fragment fragment;
    private final View root;
    private final RequestRepository repository;
    private MockRequestRepository.Tab tab = MockRequestRepository.Tab.SENT;
    private RequestRepository.Registration registration;
    private final Consumer<Intent> openAccepted;
    private final RequestProfileLoader profiles;

    public RequestTabsController(Fragment fragment, View root, RequestRepository repository,
                                 Consumer<Intent> openAccepted) {
        this(fragment, root, repository, openAccepted, ProfileRepositories.get(root.getContext())::getPublicProfile);
    }

    RequestTabsController(Fragment fragment, View root, RequestRepository repository,
                          Consumer<Intent> openAccepted, RequestProfileLoader.Source source) {
        this.fragment = fragment;
        this.root = root;
        this.repository = repository;
        this.openAccepted = openAccepted;
        profiles = new RequestProfileLoader(source,
                () -> root.post(() -> { if (canShowFeedback()) render(); }));
        fragment.getChildFragmentManager().setFragmentResultListener(ACTION_RESULT,
                fragment.getViewLifecycleOwner(), (key, result) -> handleAction(result));
        fragment.getViewLifecycleOwner().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(LifecycleOwner owner) { registration = repository.observe(() -> render()); }
            @Override public void onStop(LifecycleOwner owner) {
                if (registration != null) registration.remove();
                registration = null;
            }
            @Override public void onDestroy(LifecycleOwner owner) { profiles.close(); }
        });
    }

    public void show(MockRequestRepository.Tab selected) {
        tab = selected;
        root.setVisibility(View.VISIBLE);
        render();
    }

    public void render() {
        int heading = tab == MockRequestRepository.Tab.SENT ? R.string.request_sent
                : tab == MockRequestRepository.Tab.RECEIVED ? R.string.request_received : R.string.request_completed;
        text(root, R.id.requestHeading, fragment.getString(heading));
        List<MockRequestRepository.Item> items = repository.list(tab);
        text(root, R.id.requestCount, String.valueOf(items.size()));
        LinearLayout cards = root.findViewById(R.id.requestCards);
        cards.removeAllViews();
        StateView empty = root.findViewById(R.id.requestState);
        if (repository.error() != null) {
            empty.showError(repository.error()).primary(fragment.getString(R.string.request_retry), v -> repository.retry());
            return;
        }
        if (repository.isLoading()) { empty.showLoading(); return; }
        for (MockRequestRepository.Item item : items) {
            View card = LayoutInflater.from(root.getContext()).inflate(R.layout.item_request_card, cards, false);
            bind(card, item);
            cards.addView(card);
        }
        if (items.isEmpty()) empty.showEmpty(fragment.getString(R.string.request_empty_title,
                fragment.getString(heading)), fragment.getString(R.string.request_empty_message));
        else empty.hide();
        text(root, R.id.requestNotice, fragment.getString(tab == MockRequestRepository.Tab.SENT
                ? R.string.request_sent_notice : tab == MockRequestRepository.Tab.RECEIVED
                ? R.string.request_received_notice : R.string.request_completed_notice));
    }

    private void bind(View card, MockRequestRepository.Item item) {
        MatchCandidate profile = repository.isMock() ? MockMatchRepository.find(item.profileUid) : null;
        if (!repository.isMock()) profiles.load(item.profileUid);
        UserProfile actual = profiles.get(item.profileUid);
        boolean profileFailed = profiles.failed(item.profileUid);
        text(card, R.id.requestName, actual != null ? publicName(actual) : profile == null
                ? fragment.getString(profileFailed ? R.string.request_profile_error : R.string.request_profile_pending) : profile.name);
        text(card, R.id.requestInfo, actual != null ? actual.getDepartment() + " " + actual.getGrade() + " · " + actual.getGender()
                : profile == null ? fragment.getString(profileFailed ? R.string.request_profile_retry_message : R.string.request_profile_pending_message)
                : profile.department + " " + profile.grade + "학년 · " + profile.gender);
        boolean completed = item.status() == MockRequestRepository.Status.ACCEPTED;
        boolean ended = item.matchStatus() == MockRequestRepository.MatchStatus.ENDED;
        TextView badge = card.findViewById(R.id.requestBadge);
        badge.setVisibility(item.sent || completed ? View.VISIBLE : View.GONE);
        badge.setText(completed ? item.matchStatus() == null ? R.string.request_completed
                : ended ? R.string.request_ended : R.string.request_active : R.string.request_waiting);
        TextView profileButton = card.findViewById(R.id.requestProfile);
        profileButton.setVisibility(profileFailed || (profile != null || actual != null) && !item.sent && !completed ? View.VISIBLE : View.GONE);
        profileButton.setText(profileFailed ? R.string.request_retry : R.string.request_profile);
        profileButton.setOnClickListener(profileFailed ? v -> profiles.retry(item.profileUid)
                : v -> fragment.startActivity(ProfileDetailActivity.intent(v.getContext(), item.profileUid, Collections.emptySet())));
        // 시간 요약은 후보의 현재 공강 대신 요청 스냅샷을 표시한다.
        text(card, R.id.time, timeLabel(item));
        text(card, R.id.duration, MatchFilter.formatDuration(item.endMinute - item.startMinute) + " 겹침");
        text(card, R.id.reasons, profile == null ? "" : String.join("\n", profile.reasons));
        text(card, R.id.requestIntro, actual != null ? actual.getBio() : profile == null ? "" : profile.intro);
        card.findViewById(R.id.reasons).setVisibility(profile == null ? View.GONE : View.VISIBLE);
        card.findViewById(R.id.requestIntro).setVisibility(profile == null && actual == null ? View.GONE : View.VISIBLE);
        LinearLayout tags = card.findViewById(R.id.requestTags);
        addTag(tags, item.purpose, true);
        if (profile != null) for (String tag : profile.tags) addTag(tags, tag, tag.equals("#전공스터디") || tag.equals("#카페"));
        if (actual != null && actual.getInterestTags() != null) for (String tag : actual.getInterestTags()) addTag(tags, tag, false);
        text(card, R.id.requestMetadata, repository.isMock() ? fragment.getString(R.string.request_mock_metadata,
                fragment.getString(completed ? R.string.request_completed : item.sent
                        ? R.string.request_sent : R.string.request_received)) : java.time.Instant.ofEpochMilli(item.createdAt)
                .atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("MM.dd HH:mm", Locale.KOREA)));
        TextView secondary = card.findViewById(R.id.requestSecondary);
        TextView primary = card.findViewById(R.id.requestPrimary);
        secondary.setEnabled(!repository.isUpdating(item.id));
        primary.setEnabled(!repository.isUpdating(item.id));
        primary.setVisibility(!item.sent && !completed ? View.VISIBLE : View.GONE);
        if (completed) {
            secondary.setText(ended ? R.string.request_review : R.string.request_chat);
            secondary.setOnClickListener(v -> {
                if (ended) Toast.makeText(v.getContext(), R.string.request_review_pending, Toast.LENGTH_SHORT).show();
                else MatchChatNavigator.open((MainActivity) fragment.requireActivity(), item.id, v);
            });
        } else if (item.sent) {
            secondary.setText(R.string.request_cancel);
            secondary.setOnClickListener(v -> confirm(item, MockRequestRepository.Status.CANCELED, "취소"));
        } else {
            secondary.setText(R.string.request_reject);
            secondary.setOnClickListener(v -> confirm(item, MockRequestRepository.Status.REJECTED, "거절"));
            primary.setText(R.string.request_accept);
            primary.setOnClickListener(v -> confirm(item, MockRequestRepository.Status.ACCEPTED, "수락"));
        }
    }

    private void confirm(MockRequestRepository.Item item, MockRequestRepository.Status status, String action) {
        String key = ACTION_RESULT;
        if (fragment.getChildFragmentManager().isStateSaved()
                || fragment.getChildFragmentManager().findFragmentByTag(key) != null) return;
        MatchCandidate profile = repository.isMock() ? MockMatchRepository.find(item.profileUid) : null;
        UserProfile actual = profiles.get(item.profileUid);
        String name = actual != null ? publicName(actual) : profile == null ? "상대방" : profile.name;
        Bundle result = new Bundle();
        result.putString("id", item.id); result.putString("status", status.name());
        ConfirmDialog.show(fragment.getChildFragmentManager(), key,
                fragment.getString(R.string.request_confirm_title, name + "님의", action),
                fragment.getString(repository.isMock() ? R.string.request_confirm_message
                                : status == RequestRepository.Status.CANCELED ? R.string.request_cancel_message
                                : status == RequestRepository.Status.ACCEPTED ? R.string.request_accept_message : R.string.request_reject_message,
                        name, timeLabel(item)),
                fragment.getString(status == MockRequestRepository.Status.CANCELED ? R.string.request_cancel_confirm
                        : status == MockRequestRepository.Status.REJECTED ? R.string.request_reject : R.string.request_accept),
                fragment.getString(status == MockRequestRepository.Status.CANCELED ? R.string.request_close : R.string.request_dismiss), result);
    }

    private void handleAction(Bundle result) {
        String id = result.getString("id");
        String action = result.getString("status");
        if ("CANCELED".equals(action)) {
            repository.cancel(id, new RequestRepository.Callback() {
                @Override public void onSuccess(RequestRepository.Item item) {
                    if (canShowFeedback()) feedback(fragment.getString(R.string.request_changed));
                }
                @Override public void onError(String message) { feedback(message); }
            });
            return;
        }
        if (!"ACCEPTED".equals(action) && !"REJECTED".equals(action)) return;
        repository.respond(id, RequestRepository.Status.valueOf(action), new RequestRepository.Callback() {
            @Override public void onSuccess(RequestRepository.Item item) {
                if (!canShowFeedback()) return;
                render();
                if (item.status() == RequestRepository.Status.ACCEPTED && "ACCEPTED".equals(action)
                        && !fragment.getParentFragmentManager().isStateSaved()) {
                    openAccepted.accept(MatchAcceptedActivity.intent(root.getContext(), item));
                } else feedback(fragment.getString(R.string.request_changed));
            }
            @Override public void onError(String message) { feedback(message); }
        });
    }

    private boolean canShowFeedback() {
        return fragment.isAdded() && fragment.getView() != null && root.isAttachedToWindow()
                && fragment.getViewLifecycleOwner().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void feedback(String message) {
        if (canShowFeedback()) Toast.makeText(root.getContext(), message, Toast.LENGTH_LONG).show();
    }

    public static String timeLabel(MockRequestRepository.Item item) {
        LocalDate date = LocalDate.parse(item.meetingDate);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String day = date.equals(today) ? "오늘" : date.equals(today.minusDays(1)) ? "어제"
                : date.format(DateTimeFormatter.ofPattern("MM/dd", Locale.KOREA));
        return day + " " + minutes(item.startMinute) + " ~ " + minutes(item.endMinute);
    }

    private static String minutes(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private static void addTag(LinearLayout row, String label, boolean selected) {
        TextView chip = ChipViews.interest(row.getContext(), label, selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) params.setMarginStart(ChipViews.dp(row.getContext(), 8));
        row.addView(chip, params);
    }

    private static void text(View root, int id, String value) {
        ((TextView) root.findViewById(id)).setText(value);
    }

    private static String publicName(UserProfile profile) {
        String name = profile.getNickname();
        return name == null || name.isBlank() ? "상대방" : name;
    }
}
