package com.team4.bintime.review;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * getMatchReview 응답 → {@link MatchReview}. 형식이 틀리면 null(화면은 오류 상태).
 * Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다. 모르는 키워드는 버린다(서버에 먼저 추가된 경우).
 */
final class ReviewMapper {

    private ReviewMapper() {}

    @Nullable
    static MatchReview parse(@Nullable Object raw, String myUid) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) raw;
        Object matchId = map.get("matchId");
        Object partnerUid = map.get("partnerUid");
        Object canWrite = map.get("canWrite");
        Object received = map.get("received");
        if (!(matchId instanceof String) || !(partnerUid instanceof String) || !(canWrite instanceof Boolean)
                || !(received instanceof Map)) return null;
        ReviewRepository.Visibility visibility = visibility(((Map<?, ?>) received).get("visibility"));
        if (visibility == null) return null;
        Review mine = review(map.get("mine"), (String) matchId, myUid, (String) partnerUid);
        Review theirs = review(((Map<?, ?>) received).get("review"), (String) matchId, (String) partnerUid, myUid);
        if (visibility == ReviewRepository.Visibility.VISIBLE && theirs == null) return null;
        // 서버는 내 후기가 없으면 상대 후기를 보내지 않는다. 그래도 LOCKED 이면 받은 값을 쓰지 않는다.
        Object date = map.get("meetingDate");
        return new MatchReview((String) matchId, (String) partnerUid, (Boolean) canWrite,
                date instanceof String ? (String) date : null, minute(map.get("startMinute")), minute(map.get("endMinute")),
                mine, visibility, theirs);
    }

    @Nullable
    private static ReviewRepository.Visibility visibility(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        try {
            return ReviewRepository.Visibility.valueOf((String) value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static Review review(@Nullable Object raw, String matchId, String author, String target) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> map = (Map<?, ?>) raw;
        Satisfaction satisfaction;
        try {
            satisfaction = Satisfaction.valueOf(String.valueOf(map.get("satisfaction")));
        } catch (IllegalArgumentException e) {
            return null;
        }
        EnumSet<ReviewKeyword> keywords = EnumSet.noneOf(ReviewKeyword.class);
        Object list = map.get("keywords");
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                for (ReviewKeyword keyword : ReviewKeyword.values()) {
                    if (keyword.name().equals(item)) keywords.add(keyword);
                }
            }
        }
        Object comment = map.get("comment");
        String text = comment instanceof String ? ((String) comment).trim() : "";
        Object createdAt = map.get("createdAt");
        return new Review(matchId + "_" + author, matchId, author, target, satisfaction, new ArrayList<>(keywords),
                text.isEmpty() ? null : text, createdAt instanceof Number ? ((Number) createdAt).longValue() : 0L);
    }

    private static int minute(@Nullable Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }
}
