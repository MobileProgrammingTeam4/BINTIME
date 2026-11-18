package com.team4.bintime.data.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;



import com.team4.bintime.data.recognition.RecognitionFailure.Reason;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/** [BT-026 P4] Callable 오류 → RecognitionFailure: 서버 details 우선, 없으면 코드·원인. */
public class RecognitionCallableErrorsTest {

    private static Map<String, Object> details(String reason, Boolean retryable) {
        Map<String, Object> d = new HashMap<>();
        d.put("reason", reason);
        if (retryable != null) d.put("retryable", retryable);
        d.put("requestId", "req-1");
        return d;
    }

    @Test
    public void serverReasons() {
        Object[][] cases = {
                {"QUOTA_EXCEEDED", "RESOURCE_EXHAUSTED", Reason.QUOTA_EXCEEDED, false},
                {"AI_TIMEOUT", "DEADLINE_EXCEEDED", Reason.AI_TIMEOUT, true},
                {"AI_INVALID_OUTPUT", "INTERNAL", Reason.AI_INVALID_OUTPUT, true},
                {"AI_NOT_IMPLEMENTED", "UNIMPLEMENTED", Reason.AI_NOT_IMPLEMENTED, false},
                {"INVALID_INPUT", "INVALID_ARGUMENT", Reason.INVALID_INPUT, false},
                {"SOMETHING_NEW", "INTERNAL", Reason.INTERNAL, false},
        };
        for (Object[] c : cases) {
            RecognitionFailure f = RecognitionCallableErrors.fromParts((String) c[1],
                    details((String) c[0], (Boolean) c[3]), null);
            assertEquals((String) c[0], c[2], f.getReason());
            assertEquals((String) c[0], c[3], f.isRetryable());
            assertEquals("req-1", f.getRequestId());
        }
    }

    @Test
    public void serverRetryable_overridesDefault_andMissingUsesDefault() {
        assertFalse(RecognitionCallableErrors.fromParts("UNAVAILABLE", details("AI_UNAVAILABLE", false), null)
                .isRetryable());
        assertTrue(RecognitionCallableErrors.fromParts("UNAVAILABLE", details("AI_UNAVAILABLE", null), null)
                .isRetryable());
        // 이미지 문제로 서버가 거부해도 재시도는 서버 값을 따른다.
        assertTrue(RecognitionCallableErrors.fromParts("INVALID_ARGUMENT", details("INVALID_INPUT", true), null)
                .isRetryable());
    }

    @Test
    public void withoutDetails_usesCodeAndCause() {
        assertEquals(Reason.AI_TIMEOUT, RecognitionCallableErrors.fromParts("DEADLINE_EXCEEDED", null, null).getReason());
        assertTrue(RecognitionCallableErrors.fromParts("DEADLINE_EXCEEDED", null, null).isRetryable());
        assertEquals(Reason.NETWORK, RecognitionCallableErrors.fromParts("UNAVAILABLE", null, null).getReason());
        assertEquals(Reason.NETWORK, RecognitionCallableErrors.fromParts("INTERNAL", null,
                new RuntimeException(new SocketTimeoutException("x"))).getReason());
        assertEquals(Reason.UNAUTHENTICATED,
                RecognitionCallableErrors.fromParts("UNAUTHENTICATED", null, null).getReason());
        assertEquals(Reason.INTERNAL, RecognitionCallableErrors.fromParts("INTERNAL", null, null).getReason());
        // details 가 Map 이 아니거나 reason 이 없으면 코드로 본다.
        assertEquals(Reason.AI_TIMEOUT, RecognitionCallableErrors.fromParts("DEADLINE_EXCEEDED", "x", null).getReason());
        assertEquals(Reason.NETWORK, RecognitionCallableErrors.fromParts("UNAVAILABLE", new HashMap<>(), null).getReason());
    }

    @Test
    public void nonFunctionsExceptions() {
        assertEquals(Reason.NETWORK, RecognitionCallableErrors.fromException(new IOException(new SocketTimeoutException("x"))).getReason());
        assertEquals(Reason.NETWORK, RecognitionCallableErrors.fromException(new IOException("x")).getReason());
        assertEquals(Reason.INTERNAL, RecognitionCallableErrors.fromException(new IllegalStateException("x")).getReason());
        assertEquals(Reason.INTERNAL, RecognitionCallableErrors.fromException(null).getReason());
    }
}
