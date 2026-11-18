package com.team4.bintime.data.recognition;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.io.IOException;
import java.util.Map;

/**
 * [BT-026 P4] analyzeTimetable Callable 오류 → {@link RecognitionFailure}.
 * <ol>
 *   <li>서버 오류 details({@code {reason, retryable, requestId}}, docs/BT-024.md)가 있으면 그대로 따른다
 *       ({@link RecognitionFailure#fromServer}). 모르는 reason 은 INTERNAL.</li>
 *   <li>details 가 없으면 Callable 코드로 본다: DEADLINE_EXCEEDED(앱 타임아웃) → AI_TIMEOUT,
 *       UNAVAILABLE 이나 원인에 네트워크 오류 → NETWORK, UNAUTHENTICATED → UNAUTHENTICATED, 그 밖 INTERNAL.</li>
 * </ol>
 * 메시지·requestId 는 로그에 남기지 않는다. Firebase 예외 객체를 만들 수 없는 JVM 테스트를 위해 판정은
 * {@link #fromParts}에 둔다.
 */
final class RecognitionCallableErrors {

    private RecognitionCallableErrors() {
    }

    static RecognitionFailure fromException(Exception error) {
        if (error instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException e = (FirebaseFunctionsException) error;
            return fromParts(e.getCode() == null ? null : e.getCode().name(), e.getDetails(), e.getCause());
        }
        return fromParts(null, null, error);
    }

    /**
     * @param code {@link FirebaseFunctionsException.Code} 이름(예: "DEADLINE_EXCEEDED"). 그 enum 은 Android 클래스를
     *             써서 JVM 테스트에서 초기화할 수 없어 이름으로 받는다.
     */
    static RecognitionFailure fromParts(String code, Object details, Throwable cause) {
        if (details instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) details;
            Object reason = map.get("reason");
            if (reason instanceof String) {
                Object retryable = map.get("retryable");
                Object requestId = map.get("requestId");
                return RecognitionFailure.fromServer((String) reason,
                        retryable instanceof Boolean ? (Boolean) retryable : null,
                        requestId instanceof String ? (String) requestId : null);
            }
        }
        if ("DEADLINE_EXCEEDED".equals(code)) {
            return RecognitionFailure.of(RecognitionFailure.Reason.AI_TIMEOUT);
        }
        if ("UNAVAILABLE".equals(code) || isNetwork(cause)) {
            return RecognitionFailure.of(RecognitionFailure.Reason.NETWORK);
        }
        if ("UNAUTHENTICATED".equals(code)) {
            return RecognitionFailure.of(RecognitionFailure.Reason.UNAUTHENTICATED);
        }
        return RecognitionFailure.of(RecognitionFailure.Reason.INTERNAL);
    }

    private static boolean isNetwork(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof FirebaseNetworkException || t instanceof IOException) return true;
        }
        return false;
    }
}
