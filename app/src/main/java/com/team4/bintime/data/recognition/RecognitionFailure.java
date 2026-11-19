package com.team4.bintime.data.recognition;

/**
 * [BT-026] 시간표 인식 실패. 서버 AI 게이트웨이의 표준 오류 사유({@code functions/src/ai/errors.ts},
 * docs/BT-024.md "오류")와 같은 이름을 쓰고, 앱에서만 생기는 {@link Reason#NETWORK}를 더한다.
 * 서버가 준 retryable 이 있으면 그 값을 따른다(예: AI_UNAVAILABLE 은 설정 문제면 false).
 * requestId 는 문의용이며 화면·로그에 남기지 않는다.
 */
public final class RecognitionFailure {

    public enum Reason {
        UNAUTHENTICATED(false),
        PERMISSION_DENIED(false),
        INVALID_INPUT(false),
        QUOTA_EXCEEDED(false),
        AI_RATE_LIMITED(true),
        AI_TIMEOUT(true),
        AI_INVALID_OUTPUT(true),
        AI_BLOCKED(false),
        AI_UNAVAILABLE(true),
        AI_NOT_IMPLEMENTED(false),
        INTERNAL(false),
        /** 앱 전용: 서버에 닿지 못했다(오프라인 등). */
        NETWORK(true);

        /** 서버 표(docs/BT-024.md)의 기본 retryable. */
        final boolean defaultRetryable;

        Reason(boolean defaultRetryable) {
            this.defaultRetryable = defaultRetryable;
        }

        /** 서버 reason 문자열 → Reason. 모르는 값은 INTERNAL. */
        public static Reason fromServer(String value) {
            if (value != null) {
                for (Reason reason : values()) {
                    if (reason != NETWORK && reason.name().equals(value)) return reason;
                }
            }
            return INTERNAL;
        }
    }

    private final Reason reason;
    private final boolean retryable;
    private final String requestId;

    private RecognitionFailure(Reason reason, boolean retryable, String requestId) {
        this.reason = reason == null ? Reason.INTERNAL : reason;
        this.retryable = retryable;
        this.requestId = requestId;
    }

    /** 사유의 기본 retryable 을 쓴다. */
    public static RecognitionFailure of(Reason reason) {
        Reason r = reason == null ? Reason.INTERNAL : reason;
        return new RecognitionFailure(r, r.defaultRetryable, null);
    }

    /** Callable 오류 details({reason, retryable, requestId}) → 실패. retryable 이 없으면 사유 기본값. */
    public static RecognitionFailure fromServer(String reason, Boolean retryable, String requestId) {
        Reason r = Reason.fromServer(reason);
        return new RecognitionFailure(r, retryable != null ? retryable : r.defaultRetryable, requestId);
    }

    public Reason getReason() {
        return reason;
    }

    /** 같은 이미지로 다시 인식해도 되는지. */
    public boolean isRetryable() {
        return retryable;
    }

    public String getRequestId() {
        return requestId;
    }

    @Override
    public String toString() {
        return "RecognitionFailure{" + reason + ", retryable=" + retryable + "}";
    }
}
