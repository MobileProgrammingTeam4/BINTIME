package com.team4.bintime.review;

import androidx.annotation.Nullable;

/**
 * getMatchReview 결과(개별 matchId 하나). 공개 조건은 서버가 이미 적용했다:
 * 내 후기가 없으면 received 는 항상 null(LOCKED), 상대 후기가 없으면 NOT_YET.
 */
public final class MatchReview {
    public final String matchId;
    public final String partnerUid;
    /** 지금 작성할 수 있는지(ENDED·reviewEligible·상대 미탈퇴·아직 안 씀). 서버 판단 */
    public final boolean canWrite;
    /** 확정 약속 스냅샷. 없으면 null / -1 */
    @Nullable public final String meetingDate;
    public final int startMinute, endMinute;
    @Nullable public final Review mine;
    public final ReviewRepository.Visibility visibility;
    /** visibility == VISIBLE 일 때만 있다. */
    @Nullable public final Review received;

    public MatchReview(String matchId, String partnerUid, boolean canWrite, @Nullable String meetingDate,
                       int startMinute, int endMinute, @Nullable Review mine,
                       ReviewRepository.Visibility visibility, @Nullable Review received) {
        this.matchId = matchId;
        this.partnerUid = partnerUid;
        this.canWrite = canWrite;
        this.meetingDate = meetingDate;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.mine = mine;
        this.visibility = visibility;
        this.received = visibility == ReviewRepository.Visibility.VISIBLE ? received : null;
    }

    public boolean myReviewWritten() {
        return mine != null;
    }
}
