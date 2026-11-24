package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * leaveChatRoom 실패 분류 (BT-043, docs/BT-043.md). Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * 서버는 정책 실패를 failed-precondition + details.reason(ACTIVE_MATCH·RETRY_LATER·INVALID_ROOM)으로 보낸다.
 */
public enum ChatLeaveFailure {
    /** 방에 진행 중(ACTIVE) 약속이 남아 있다. 자동 종료가 늦어진 경우도 포함. 매칭 종료를 먼저 해야 한다. */
    ACTIVE_MATCH,
    /** 다른 작업(채팅 연결·종료 상태 반영)과 겹쳤거나 일시 오류. 잠시 뒤 다시 누르면 된다. */
    RETRY_LATER,
    /** 참여자가 아니거나 학교 이메일 미인증. 다시 눌러도 같다. */
    PERMISSION,
    /** 로그인 만료 */
    SIGNED_OUT,
    /** 잘못된 방 데이터·입력 등 알 수 없는 오류 */
    GENERIC;

    /**
     * @param code    FirebaseFunctionsException.Code 이름(예: "FAILED_PRECONDITION"). Functions 오류가 아니면 null(네트워크)
     * @param details 서버 details. 없으면 null
     */
    static ChatLeaveFailure from(@Nullable String code, @Nullable Object details) {
        if (code == null) return RETRY_LATER;
        switch (code) {
            case "FAILED_PRECONDITION": {
                Object reason = details instanceof Map ? ((Map<?, ?>) details).get("reason") : null;
                if ("ACTIVE_MATCH".equals(reason)) return ACTIVE_MATCH;
                if ("RETRY_LATER".equals(reason)) return RETRY_LATER;
                return GENERIC; // INVALID_ROOM·모르는 사유
            }
            case "UNAUTHENTICATED": return SIGNED_OUT;
            case "PERMISSION_DENIED": return PERMISSION;
            case "UNAVAILABLE":
            case "DEADLINE_EXCEEDED":
            case "INTERNAL":
            case "RESOURCE_EXHAUSTED": return RETRY_LATER;
            default: return GENERIC; // INVALID_ARGUMENT·NOT_FOUND 등
        }
    }
}
