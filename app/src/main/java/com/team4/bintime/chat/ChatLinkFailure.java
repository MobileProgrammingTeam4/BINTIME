package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * ensureMatchChat 실패 분류 (BT-034 §3). Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * 서버 실패는 failed-precondition + details.reason, 그 밖의 서버 오류는 unavailable 로 온다(functions/src/chat/linkCallable.ts).
 */
public final class ChatLinkFailure {

    public enum Kind {
        /** retryAfterMillis 뒤에 사용자가 다시 시도할 수 있다. 자동으로 다시 부르지 않는다. */
        RETRY_LATER,
        /** 같은 두 사람의 다른 매칭(blockingMatchId)이 연결 중이다. 그 매칭을 먼저 준비한 뒤 원래 매칭을 다시 준비한다. */
        PAIR_BUSY,
        /** 영구 불일치·권한 없음. 다시 시도해도 바뀌지 않아 관리자 확인 대상이다. */
        PERMANENT,
        /** 로그인 만료 */
        UNAUTHENTICATED
    }

    /** 서버가 시간을 주지 않은 일시 오류의 대기 시간. 서버 실패 후 재시도 간격(nextAttemptAt, 10초)과 같다. */
    static final long DEFAULT_RETRY_MILLIS = 10_000;
    /** 비정상적으로 긴 값은 lease(120초)로 제한한다. */
    static final long MAX_RETRY_MILLIS = 120_000;
    static final long MIN_RETRY_MILLIS = 1_000;
    private static final Pattern MATCH_ID = Pattern.compile("^[a-f0-9]{64}$");

    public final Kind kind;
    public final long retryAfterMillis;
    @Nullable public final String blockingMatchId;
    /** 로그·디버그용 서버 사유 또는 코드 */
    public final String reason;

    private ChatLinkFailure(Kind kind, long retryAfterMillis, @Nullable String blockingMatchId, String reason) {
        this.kind = kind;
        this.retryAfterMillis = retryAfterMillis;
        this.blockingMatchId = blockingMatchId;
        this.reason = reason;
    }

    static ChatLinkFailure retryLater(long millis, String reason) {
        return new ChatLinkFailure(Kind.RETRY_LATER, clamp(millis), null, reason);
    }

    static ChatLinkFailure permanent(String reason) {
        return new ChatLinkFailure(Kind.PERMANENT, 0, null, reason);
    }

    /** 네트워크 끊김 등 Functions 오류가 아닌 예외 */
    static ChatLinkFailure network() {
        return retryLater(DEFAULT_RETRY_MILLIS, "NETWORK");
    }

    /**
     * @param code    FirebaseFunctionsException.Code 이름(예: "FAILED_PRECONDITION")
     * @param details 서버 details. 없으면 null
     */
    static ChatLinkFailure from(@Nullable String code, @Nullable Object details) {
        if (code == null) return network();
        switch (code) {
            case "FAILED_PRECONDITION":
                return fromReason(details instanceof Map ? (Map<?, ?>) details : null);
            case "UNAUTHENTICATED":
                return new ChatLinkFailure(Kind.UNAUTHENTICATED, 0, null, code);
            case "PERMISSION_DENIED": // 참여자 아님·학교 이메일 미인증
            case "NOT_FOUND":
            case "INVALID_ARGUMENT":
                return permanent(code);
            default: // UNAVAILABLE·DEADLINE_EXCEEDED·INTERNAL·RESOURCE_EXHAUSTED 등 일시 오류
                return retryLater(DEFAULT_RETRY_MILLIS, code);
        }
    }

    private static ChatLinkFailure fromReason(@Nullable Map<?, ?> details) {
        Object raw = details == null ? null : details.get("reason");
        String reason = raw instanceof String ? (String) raw : "UNKNOWN";
        switch (reason) {
            case "CHAT_PAIR_BUSY": {
                Object id = details.get("blockingMatchId");
                if (id instanceof String && MATCH_ID.matcher((String) id).matches()) {
                    return new ChatLinkFailure(Kind.PAIR_BUSY, 0, (String) id, reason);
                }
                return retryLater(DEFAULT_RETRY_MILLIS, reason);
            }
            case "CHAT_LINK_BUSY":
            case "CHAT_RETRY_LATER": {
                Object after = details.get("retryAfterMillis");
                return retryLater(after instanceof Number ? ((Number) after).longValue() : DEFAULT_RETRY_MILLIS, reason);
            }
            case "INVALID_MATCH":
            case "INVALID_ROOM":
            case "ROOM_ARCHIVED":
            case "ROOM_MEMBERS_CHANGED":
            case "ROOM_ENTRY_CHANGED":
                return permanent(reason);
            default: // STALE_LINK(다른 작업이 진행 중)와 앞으로 추가될 사유는 잠시 후 재시도
                return retryLater(DEFAULT_RETRY_MILLIS, reason);
        }
    }

    private static long clamp(long millis) {
        return Math.max(MIN_RETRY_MILLIS, Math.min(MAX_RETRY_MILLIS, millis));
    }
}
