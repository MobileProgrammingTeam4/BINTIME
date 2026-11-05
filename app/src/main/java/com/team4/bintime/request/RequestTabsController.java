package com.team4.bintime.request;

import android.view.LayoutInflater;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.team4.bintime.R;
import com.team4.bintime.MainActivity;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.ConfirmDialog;
import com.team4.bintime.common.StateView;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.matching.ProfileDetailActivity;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** BT-007 요청 탭 UI. 서버 연결 전 Mock 데이터만 변경한다. */
public final class RequestTabsController {
    private final Fragment fragment;
    private final View root;
    private final MockRequestRepository repository;
    private MockRequestRepository.Tab tab = MockRequestRepository.Tab.SENT;

    public RequestTabsController(Fragment fragment, View root, MockRequestRepository repository,
                                 Consumer<Intent> openAccepted) {
        this.fragment = fragment;
        this.root = root;
        this.repository = repository;
        // 결과 키에 요청 ID와 동작을 담아 회전으로 복원된 다이얼로그도 정확히 처리한다.
        for (MockRequestRepository.Tab t : MockRequestRepository.Tab.values()) {
            for (MockRequestRepository.Item item : repository.list(t)) {
                for (MockRequestRepository.Status status : MockRequestRepository.Status.values()) {
                    String key = key(item, status);
                    fragment.getChildFragmentManager().setFragmentResultListener(key,
                            fragment.getViewLifecycleOwner(), (k, result) -> {
                                boolean changed = repository.respond(item.id, status);
                                render();
                                if (changed && status == MockRequestRepository.Status.ACCEPTED) {
                                    openAccepted.accept(MatchAcceptedActivity.intent(root.getContext(), item));
                                } else {
                                    Toast.makeText(root.getContext(), changed ? R.string.request_changed
                                            : R.string.request_failed, Toast.LENGTH_SHORT).show();
                                }
                            });
                }
            }
        }
    }

    public void show(MockRequestRepository.Tab selected) {
        tab = selected;
        root.setVisibility(View.VISIBLE);
        render();
    }

    private void render() {
        int heading = tab == MockRequestRepository.Tab.SENT ? R.string.request_sent
                : tab == MockRequestRepository.Tab.RECEIVED ? R.string.request_received : R.string.request_completed;
        text(root, R.id.requestHeading, fragment.getString(heading));
        List<MockRequestRepository.Item> items = repository.list(tab);
        text(root, R.id.requestCount, String.valueOf(items.size()));
        LinearLayout cards = root.findViewById(R.id.requestCards);
        cards.removeAllViews();
        for (MockRequestRepository.Item item : items) {
            View card = LayoutInflater.from(root.getContext()).inflate(R.layout.item_request_card, cards, false);
            bind(card, item);
            cards.addView(card);
        }
        StateView empty = root.findViewById(R.id.requestState);
        if (items.isEmpty()) empty.showEmpty(fragment.getString(R.string.request_empty_title,
                fragment.getString(heading)), fragment.getString(R.string.request_empty_message));
        else empty.hide();
        text(root, R.id.requestNotice, fragment.getString(tab == MockRequestRepository.Tab.SENT
                ? R.string.request_sent_notice : tab == MockRequestRepository.Tab.RECEIVED
                ? R.string.request_received_notice : R.string.request_completed_notice));
    }

    private void bind(View card, MockRequestRepository.Item item) {
        MatchCandidate profile = MockMatchRepository.find(item.profileUid);
        if (profile == null) return;
        text(card, R.id.requestName, profile.name);
        text(card, R.id.requestInfo, profile.department + " " + profile.grade + "학년 · " + profile.gender);
        boolean completed = item.status() == MockRequestRepository.Status.ACCEPTED;
        boolean ended = item.matchStatus() == MockRequestRepository.MatchStatus.ENDED;
        TextView badge = card.findViewById(R.id.requestBadge);
        badge.setVisibility(item.sent || completed ? View.VISIBLE : View.GONE);
        badge.setText(completed ? ended ? R.string.request_ended : R.string.request_active : R.string.request_waiting);
        View profileButton = card.findViewById(R.id.requestProfile);
        profileButton.setVisibility(!item.sent && !completed ? View.VISIBLE : View.GONE);
        profileButton.setOnClickListener(v -> fragment.startActivity(ProfileDetailActivity.intent(
                v.getContext(), profile.uid, Collections.emptySet())));
        // 시간 요약은 후보의 현재 공강 대신 요청 스냅샷을 표시한다.
        text(card, R.id.time, timeLabel(item));
        text(card, R.id.duration, MatchFilter.formatDuration(item.endMinute - item.startMinute) + " 겹침");
        text(card, R.id.reasons, String.join("\n", profile.reasons));
        text(card, R.id.requestIntro, profile.intro);
        LinearLayout tags = card.findViewById(R.id.requestTags);
        addTag(tags, profile.purpose, true);
        for (String tag : profile.tags) addTag(tags, tag, tag.equals("#전공스터디") || tag.equals("#카페"));
        text(card, R.id.requestMetadata, fragment.getString(R.string.request_mock_metadata,
                fragment.getString(completed ? R.string.request_completed : item.sent
                        ? R.string.request_sent : R.string.request_received)));
        TextView secondary = card.findViewById(R.id.requestSecondary);
        TextView primary = card.findViewById(R.id.requestPrimary);
        primary.setVisibility(!item.sent && !completed ? View.VISIBLE : View.GONE);
        if (completed) {
            secondary.setText(ended ? R.string.request_review : R.string.request_chat);
            secondary.setOnClickListener(v -> {
                if (ended) Toast.makeText(v.getContext(), R.string.request_review_pending, Toast.LENGTH_SHORT).show();
                else ((MainActivity) fragment.requireActivity()).selectTab(R.id.nav_chat);
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
        String key = key(item, status);
        if (fragment.getChildFragmentManager().isStateSaved()
                || fragment.getChildFragmentManager().findFragmentByTag(key) != null) return;
        MatchCandidate profile = MockMatchRepository.find(item.profileUid);
        ConfirmDialog.show(fragment.getChildFragmentManager(), key,
                fragment.getString(R.string.request_confirm_title, profile.name + "님의", action),
                fragment.getString(R.string.request_confirm_message, profile.name, timeLabel(item)),
                fragment.getString(status == MockRequestRepository.Status.CANCELED ? R.string.request_cancel_confirm
                        : status == MockRequestRepository.Status.REJECTED ? R.string.request_reject : R.string.request_accept),
                fragment.getString(status == MockRequestRepository.Status.CANCELED ? R.string.request_close : R.string.request_dismiss));
    }

    private static String key(MockRequestRepository.Item item, MockRequestRepository.Status status) {
        return "request:" + item.id + ":" + status.name();
    }

    static String timeLabel(MockRequestRepository.Item item) {
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
}
