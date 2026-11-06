package com.team4.bintime.review;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 후기 초안 모델. Firestore reviews/{reviewId} 계약은 BT-044 에서 확정한다.
 * authorUid 는 서버가 Auth 에서 정하고, 상대 후기 공개 여부도 서버가 판단한다.
 */
public final class Review {
    public final String reviewId;
    public final String matchId;
    public final String authorUid;
    public final String targetUid;
    public final Satisfaction satisfaction;
    /** 선택한 순서와 무관하게 enum 순서로 정렬해 둔다. */
    public final List<ReviewKeyword> keywords;
    /** 한 줄 후기. 없으면 null */
    @Nullable public final String comment;
    public final long createdAt;

    Review(String reviewId, String matchId, String authorUid, String targetUid, Satisfaction satisfaction,
           List<ReviewKeyword> keywords, @Nullable String comment, long createdAt) {
        this.reviewId = reviewId;
        this.matchId = matchId;
        this.authorUid = authorUid;
        this.targetUid = targetUid;
        this.satisfaction = satisfaction;
        this.keywords = Collections.unmodifiableList(keywords);
        this.comment = comment;
        this.createdAt = createdAt;
    }
}
