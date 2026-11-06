package com.team4.bintime.review;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * BT-010 UI 시연 전용. 메모리 데이터만 바꾸며 실제 권한·Firestore·공개 조건 검사를 대신하지 않는다.
 * 시드: 지은 방은 양쪽 모두 작성, 서연 방은 상대만 작성(내가 쓰면 바로 공개), 그 밖의 방은 상대 미작성.
 */
public final class MockReviewRepository implements ReviewRepository {

    interface Scheduler {
        void schedule(Runnable task, long delayMillis);
    }

    static final String MY_UID = "me";
    static final long LOAD_DELAY_MILLIS = 300;

    private static MockReviewRepository shared;

    public static MockReviewRepository shared() {
        if (shared == null) {
            Handler handler = new Handler(Looper.getMainLooper());
            shared = new MockReviewRepository(handler::postDelayed, System::currentTimeMillis);
        }
        return shared;
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    /** matchId → 내가 쓴 후기 */
    private final Map<String, Review> written = new HashMap<>();
    /** matchId → 상대가 나에게 쓴 후기 */
    private final Map<String, Review> received = new HashMap<>();
    private int nextId = 1;

    MockReviewRepository(Scheduler scheduler, LongSupplier clock) {
        this.scheduler = scheduler;
        this.clock = clock;
        seed();
    }

    @Override
    public void loadMyReviewStatus(String matchId, StatusCallback callback) {
        callback.onResult(written.containsKey(matchId));
    }

    @Override
    public void loadReceivedReview(String matchId, ReceivedCallback callback) {
        scheduler.schedule(() -> {
            if (matchId == null) {
                callback.onError();
            } else if (!written.containsKey(matchId)) {
                callback.onResult(Visibility.LOCKED, null);
            } else if (!received.containsKey(matchId)) {
                callback.onResult(Visibility.NOT_YET, null);
            } else {
                callback.onResult(Visibility.VISIBLE, received.get(matchId));
            }
        }, LOAD_DELAY_MILLIS);
    }

    @Override
    public void submitReview(String matchId, String targetUid, Satisfaction satisfaction,
                             Set<ReviewKeyword> keywords, @Nullable String comment, SubmitCallback callback) {
        String text = comment == null ? "" : comment.trim();
        if (matchId == null || targetUid == null || satisfaction == null
                || text.length() > COMMENT_MAX_LENGTH || written.containsKey(matchId)) {
            callback.onError();
            return;
        }
        written.put(matchId, review(matchId, MY_UID, targetUid, satisfaction,
                keywords == null ? EnumSet.noneOf(ReviewKeyword.class) : keywords,
                text.isEmpty() ? null : text));
        callback.onSuccess();
    }

    private Review review(String matchId, String author, String target, Satisfaction satisfaction,
                          Set<ReviewKeyword> keywords, @Nullable String comment) {
        // EnumSet 은 enum 순서로 순회하므로 선택 순서와 무관하게 정렬된다.
        EnumSet<ReviewKeyword> ordered = EnumSet.noneOf(ReviewKeyword.class);
        ordered.addAll(keywords);
        List<ReviewKeyword> sorted = new ArrayList<>(ordered);
        return new Review("mock-review-" + nextId++, matchId, author, target, satisfaction, sorted,
                comment, clock.getAsLong());
    }

    private void seed() {
        written.put("match-jieun", review("match-jieun", MY_UID, "mock-jieun", Satisfaction.GREAT,
                EnumSet.of(ReviewKeyword.FOCUSED_STUDY, ReviewKeyword.KIND_MANNER), null));
        received.put("match-jieun", review("match-jieun", "mock-jieun", MY_UID, Satisfaction.GREAT,
                set(ReviewKeyword.ON_TIME, ReviewKeyword.QUICK_REPLY, ReviewKeyword.CLEAN_PARTING,
                        ReviewKeyword.FOCUSED_STUDY), "정말 재밌었던 하루였어요"));
        received.put("match-seoyeon", review("match-seoyeon", "mock-seoyeon", MY_UID, Satisfaction.GOOD,
                set(ReviewKeyword.KIND_MANNER, ReviewKeyword.GOOD_CONVERSATION), "다음에 또 같이 공부해요!"));
    }

    private static Set<ReviewKeyword> set(ReviewKeyword... keywords) {
        return EnumSet.copyOf(Arrays.asList(keywords));
    }
}
