package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** 응답 순서를 통제해 전송 결과 처리를 검증한다 (PR #96 리뷰 1). */
public class ChatSendAttemptTest {

    private static final String ROOM = "room";

    private final ChatSendPolicy policy = new ChatSendPolicy();
    private int created, successes, errors, releases;
    private final ChatSendPolicy.KeyFactory keys = () -> "-k" + (++created);
    private final ChatRepository.SendCallback callback = new ChatRepository.SendCallback() {
        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onError() {
            errors++;
        }
    };

    private ChatSendAttempt attempt(String content) {
        return new ChatSendAttempt(policy, ROOM, policy.keyFor(ROOM, content, keys), () -> releases++, callback);
    }

    @Test
    public void timeoutThenLateSuccess_resendOfSameContentReusesOriginalKey() {
        ChatSendAttempt first = attempt("안녕");
        first.timeout();                  // 10초 안에 응답 없음 → 화면은 실패
        first.finish(true);               // 앞선 쓰기의 성공 응답이 늦게 도착

        assertEquals(0, successes);
        assertEquals(1, errors);
        assertEquals(1, releases);

        // 사용자가 같은 내용을 다시 보낸다 → 같은 키 → 규칙이 거부하고 "내 메시지 있음"으로 성공 처리된다
        ChatSendAttempt retry = attempt("안녕");
        assertEquals(first.key(), retry.key());
        assertEquals(1, created);
    }

    @Test
    public void timeoutThenLateFailure_isIgnoredAndKeyKept() {
        ChatSendAttempt first = attempt("안녕");
        first.timeout();
        first.finish(false);

        assertEquals(1, errors);
        assertEquals(1, releases);
        assertEquals(first.key(), attempt("안녕").key());
    }

    @Test
    public void successBeforeTimeout_reportsOnceAndNextSameContentIsNewMessage() {
        ChatSendAttempt first = attempt("ㅋㅋ");
        first.finish(true);
        first.timeout();                  // 이미 응답했으므로 무시

        assertEquals(1, successes);
        assertEquals(0, errors);
        assertEquals(1, releases);
        assertNotEquals(first.key(), attempt("ㅋㅋ").key());
    }

    @Test
    public void failureBeforeTimeout_keepsKeyForRetry() {
        ChatSendAttempt first = attempt("안녕");
        first.finish(false);

        assertEquals(1, errors);
        assertEquals(first.key(), attempt("안녕").key());
    }
}
