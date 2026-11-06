package com.team4.bintime.report;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 신고 화면(ReportActivity)이 만드는 입력 계약. BT-051(B)에서 ReportRepository.submit(form) 의 입력으로 쓴다.
 * <ul>
 *   <li>reportedUid: 신고 대상 UID (필수)</li>
 *   <li>matchId: 신고가 일어난 매칭. 채팅 밖(프로필 등)에서 신고하면 null</li>
 *   <li>reasons: {@link ReportReason} 1개 이상</li>
 *   <li>detail: 직접 남기기. 앞뒤 공백을 지우고 비면 null, 최대 {@link #DETAIL_MAX_LENGTH}자</li>
 *   <li>alsoBlock: "이 사용자를 함께 차단하기". 실제 차단은 BT-046(B)</li>
 * </ul>
 * 신고자 UID 는 넣지 않는다. 서버가 Auth 토큰에서 확인한다(요청 본문의 UID 를 신원으로 쓰지 않는다).
 */
public final class ReportForm {

    public static final int DETAIL_MAX_LENGTH = 500;

    public enum Problem { MISSING_TARGET, NO_REASON, DETAIL_TOO_LONG }

    public final String reportedUid;
    @Nullable public final String matchId;
    public final Set<ReportReason> reasons;
    @Nullable public final String detail;
    public final boolean alsoBlock;

    public ReportForm(String reportedUid, @Nullable String matchId, Set<ReportReason> reasons,
                      @Nullable String detail, boolean alsoBlock) {
        this.reportedUid = reportedUid;
        this.matchId = matchId;
        EnumSet<ReportReason> copy = EnumSet.noneOf(ReportReason.class);
        if (reasons != null) copy.addAll(reasons);
        this.reasons = Collections.unmodifiableSet(copy);
        String trimmed = detail == null ? "" : detail.trim();
        this.detail = trimmed.isEmpty() ? null : trimmed;
        this.alsoBlock = alsoBlock;
    }

    /** 문제가 없으면 null. 화면 검사와 같은 규칙을 서버도 다시 검사해야 한다. */
    @Nullable
    public Problem validate() {
        if (reportedUid == null || reportedUid.trim().isEmpty()) return Problem.MISSING_TARGET;
        if (reasons.isEmpty()) return Problem.NO_REASON;
        if (detail != null && detail.length() > DETAIL_MAX_LENGTH) return Problem.DETAIL_TOO_LONG;
        return null;
    }
}
