package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * suggestChatReplies 실패 분류 (docs/BT-024.md 2장 오류 표). Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * 어떤 실패든 칩은 숨기고 채팅·직접 입력은 그대로 쓸 수 있다.
 */
public enum ReplySuggestionFailure {
    /** 잠시 뒤 반짝이 버튼을 다시 누르면 된다(시간 초과·형식 오류·요청 많음·일시 장애·네트워크). */
    RETRY,
    /** 오늘 횟수를 모두 썼다. 직접 입력을 유도한다. */
    QUOTA,
    /** 권한 없음(방에서 나감·참여자 아님·학교 이메일 미인증). 다시 눌러도 같다. */
    PERMISSION,
    /** AI 가 이 대화에 응답할 수 없다. */
    BLOCKED,
    /** 키·모델 설정 문제나 미구현. 이 화면에서는 반짝이 버튼을 숨긴다. */
    UNAVAILABLE,
    /** 로그인 만료 */
    SIGNED_OUT,
    /** 앱 버그(입력 형식)·알 수 없는 오류 */
    GENERIC;

    /**
     * @param code    FirebaseFunctionsException.Code 이름(예: "DEADLINE_EXCEEDED"). Functions 오류가 아니면 null
     * @param details 서버 details({@code reason, retryable}). 없으면 null
     */
    static ReplySuggestionFailure from(@Nullable String code, @Nullable Object details) {
        if (code == null) return RETRY; // 네트워크 끊김 등 Functions 오류가 아닌 예외
        Map<?, ?> map = details instanceof Map ? (Map<?, ?>) details : null;
        Object reason = map == null ? null : map.get("reason");
        boolean retryable = map != null && Boolean.TRUE.equals(map.get("retryable"));
        if (reason instanceof String) {
            switch ((String) reason) {
                case "UNAUTHENTICATED": return SIGNED_OUT;
                case "PERMISSION_DENIED": return PERMISSION;
                case "QUOTA_EXCEEDED": return QUOTA;
                case "AI_BLOCKED": return BLOCKED;
                case "AI_NOT_IMPLEMENTED": return UNAVAILABLE;
                case "AI_UNAVAILABLE": return retryable ? RETRY : UNAVAILABLE;
                case "AI_RATE_LIMITED":
                case "AI_TIMEOUT":
                case "AI_INVALID_OUTPUT": return RETRY;
                default: return GENERIC; // INVALID_INPUT·INTERNAL·모르는 사유
            }
        }
        // details 가 없는 오류: 플랫폼이 만든 코드만 본다.
        switch (code) {
            case "UNAUTHENTICATED": return SIGNED_OUT;
            case "PERMISSION_DENIED": return PERMISSION;
            case "UNAVAILABLE":
            case "DEADLINE_EXCEEDED":
            case "RESOURCE_EXHAUSTED": return RETRY;
            default: return GENERIC;
        }
    }
}
