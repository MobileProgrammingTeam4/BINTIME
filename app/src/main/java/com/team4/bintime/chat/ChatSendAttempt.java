package com.team4.bintime.chat;

/**
 * 메시지 한 번 보내기의 결과 처리 (BT-022). 시간 초과와 서버 응답 중 먼저 온 것 하나만 화면에 알린다.
 * Firebase·Handler 에 의존하지 않아 응답 순서를 통제한 JVM 테스트로 검증한다.
 *
 * <p>시간 초과 뒤 늦게 온 성공 응답은 화면에 알리지 않고, 재전송 키도 <b>지우지 않는다</b>.
 * 화면은 실패로 알고 있어 사용자가 같은 내용을 다시 보내기 때문이다. 키가 남아 있으면 재전송이 같은 키를 쓰고,
 * 규칙(!data.exists())이 거부한 뒤 그 키의 값이 내 메시지라 성공으로 처리된다 → 메시지가 두 번 생기지 않는다(PR #96 리뷰 1).
 */
final class ChatSendAttempt {

    private final ChatSendPolicy policy;
    private final String matchId;
    private final String key;
    private final Runnable release;
    private final ChatRepository.SendCallback callback;
    private boolean done;

    /** release: 화면에 결과를 알릴 때 한 번 실행(방별 전송 중 표시 해제). */
    ChatSendAttempt(ChatSendPolicy policy, String matchId, String key, Runnable release,
                    ChatRepository.SendCallback callback) {
        this.policy = policy;
        this.matchId = matchId;
        this.key = key;
        this.release = release;
        this.callback = callback;
    }

    String key() {
        return key;
    }

    /** 응답이 오기 전에 시간이 다 됐다. 재전송 키는 그대로 둔다. */
    void timeout() {
        if (done) return;
        done = true;
        release.run();
        callback.onError();
    }

    /** 서버 응답(또는 거부 후 확인 결과). 이미 시간 초과로 알렸으면 아무것도 하지 않는다. */
    void finish(boolean success) {
        if (done) return;
        done = true;
        if (success) policy.confirmed(matchId, key);
        release.run();
        if (success) callback.onSuccess();
        else callback.onError();
    }
}
