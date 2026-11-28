package com.team4.bintime.review;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * submitReview·getMatchReview 실패 분류(docs/BT-044.md). Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 */
public enum ReviewFailure {
    /** 이미 이 매칭에 후기를 남겼다. 작성 화면 대신 조회 화면을 연다. */
    ALREADY_REVIEWED,
    /** 진행 중·시작 전 종료·탈퇴 종료 등 후기 대상이 아니다(reviewEligible 은 서버 확정 값). */
    NOT_ELIGIBLE,
    /** 매칭 뒤 상대가 탈퇴했다. */
    PARTNER_DELETED,
    /** 참여자가 아니거나 학교 이메일 미인증·로그인 만료 */
    PERMISSION,
    /** 잠시 뒤 다시 시도(네트워크·일시 장애) */
    RETRY,
    /** 입력 형식 오류·알 수 없는 오류 */
    GENERIC;

    /**
     * @param code    FirebaseFunctionsException.Code 이름. Functions 오류가 아니면 null(네트워크 등)
     * @param details 서버 details({@code reason, requestId}). 없으면 null
     */
    static ReviewFailure from(@Nullable String code, @Nullable Object details) {
        if (code == null) return RETRY;
        Map<?, ?> map = details instanceof Map ? (Map<?, ?>) details : null;
        Object reason = map == null ? null : map.get("reason");
        if (reason instanceof String) {
            switch ((String) reason) {
                case "ALREADY_REVIEWED": return ALREADY_REVIEWED;
                case "NOT_ELIGIBLE": return NOT_ELIGIBLE;
                case "PARTNER_DELETED": return PARTNER_DELETED;
                case "UNAUTHENTICATED":
                case "PERMISSION_DENIED": return PERMISSION;
                case "RETRY_LATER": return RETRY;
                default: return GENERIC;
            }
        }
        switch (code) {
            case "UNAUTHENTICATED":
            case "PERMISSION_DENIED": return PERMISSION;
            case "UNAVAILABLE":
            case "DEADLINE_EXCEEDED":
            case "INTERNAL": return RETRY;
            default: return GENERIC;
        }
    }
}
